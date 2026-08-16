package com.das.p1stouch.printer.mqtt

import com.das.p1stouch.printer.hms.HmsCodes
import com.das.p1stouch.state.AMSTray
import com.das.p1stouch.state.GcodeState
import com.das.p1stouch.state.PrinterState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses a raw MQTT report payload into [PrinterState]. Every field name
 * and conversion below was read directly out of bambulabs_api's
 * mqtt_client.py source (not guessed) -- see the comment on each line for
 * where it came from. Deliberately permissive (JsonObject + defensive
 * accessors, not a strict @Serializable data class): several fields are
 * documented as "int | str | None" in the library itself (e.g. mc_percent
 * can apparently arrive as the literal string "Unknown"), so a rigid typed
 * deserializer would crash on exactly the payloads this needs to tolerate.
 */
object PrinterTelemetry {
    private val json = Json { ignoreUnknownKeys = true }

    /** [previous] carries forward anything a given payload doesn't touch
     * (MQTT report pushes are often partial updates, not full snapshots). */
    fun parse(payload: String, previous: PrinterState, hmsCodes: HmsCodes): PrinterState {
        val root = json.parseToJsonElement(payload).jsonObject
        val print = root["print"]?.jsonObject ?: return previous

        return previous.copy(
            nozzleTemp = print.doubleField("nozzle_temper") ?: previous.nozzleTemp,
            nozzleTarget = print.doubleField("nozzle_target_temper") ?: previous.nozzleTarget,
            bedTemp = print.doubleField("bed_temper") ?: previous.bedTemp,
            bedTarget = print.doubleField("bed_target_temper") ?: previous.bedTarget,
            chamberTemp = print.doubleField("chamber_temper") ?: previous.chamberTemp,
            gcodeState = print.stringField("gcode_state")?.let(::mapGcodeState) ?: previous.gcodeState,
            printPercent = print.intField("mc_percent") ?: previous.printPercent,
            currentLayer = print.intField("layer_num") ?: previous.currentLayer,
            totalLayer = print.intField("total_layer_num") ?: previous.totalLayer,
            // mc_remaining_time's unit is inconsistently documented (the
            // library's own docstring says seconds; Bambu-community
            // convention says minutes). Treating as minutes to match this
            // app's PrinterState.remainingMinutes field/UI label --
            // confirm against a live print with a known ETA (M3
            // verification) and fix the divisor here if it's actually
            // seconds.
            remainingMinutes = print.intField("mc_remaining_time") ?: previous.remainingMinutes,
            currentFile = print.stringField("subtask_name") ?: previous.currentFile,
            // Deliberately NOT porting bambulabs_api's get_print_speed(),
            // which reads "spd_mag" (a 0-100+ percentage magnitude, default
            // 100) -- that doesn't match a 1-4 Silent/Standard/Sport/
            // Ludicrous level at all and looks like a latent bug in the
            // library. Reading "spd_lvl" directly instead, per Bambu's
            // documented local MQTT schema; verify this field is actually
            // present on real telemetry (M3 verification).
            speedLevel = print.intField("spd_lvl") ?: previous.speedLevel,
            lightOn = print.lightsReportMode()?.let { it == "on" } ?: previous.lightOn,
            fanSpeeds = print.intField("fan_gear")?.let(::unpackFanGear) ?: previous.fanSpeeds,
            amsTrays = print.amsTraysOrNull() ?: previous.amsTrays,
            amsBusy = print.isAmsBusy() ?: previous.amsBusy,
            amsTemp = print.amsUnitTemp() ?: previous.amsTemp,
            amsHumidityPercent = print.amsUnitHumidityPercent() ?: previous.amsHumidityPercent,
            hmsErrors = print.hmsEntriesOrNull(hmsCodes) ?: previous.hmsErrors,
        )
    }

    private fun mapGcodeState(raw: String): GcodeState = when (raw) {
        "IDLE" -> GcodeState.IDLE
        "PREPARE" -> GcodeState.RUNNING
        "RUNNING" -> GcodeState.RUNNING
        "PAUSE" -> GcodeState.PAUSE
        "FINISH" -> GcodeState.FINISH
        "FAILED" -> GcodeState.FAILED
        else -> GcodeState.UNKNOWN
    }

    // fan_gear bit-packs all three fans into one int (mqtt_client.py's
    // get_part/aux/chamber_fan_speed()): part = n%256, aux = (n>>8)%256,
    // chamber = n>>16. Each is raw PWM 0-255; convert to percent for the UI.
    private fun unpackFanGear(fanGear: Int): Map<String, Int> = mapOf(
        "part" to pwmToPercent(fanGear % 256),
        "aux" to pwmToPercent((fanGear shr 8) % 256),
        "chamber" to pwmToPercent(fanGear shr 16),
    )

    private fun pwmToPercent(pwm: Int): Int = Math.round(pwm.coerceIn(0, 255) / 255.0 * 100).toInt()

    // lights_report is a list of {"mode": ...}; state is entry [0]'s mode,
    // "unknown" if the list is empty (mqtt_client.py's get_light_state()).
    private fun JsonObject.lightsReportMode(): String? {
        val list = this["lights_report"] as? JsonArray ?: return null
        val first = list.firstOrNull()?.jsonObject ?: return null
        return first.stringField("mode")
    }

    // raw["print"]["ams"]: skip entirely if ams_exist_bits == "0" (string)
    // or the block itself is absent. Units at ams.ams (list), each with a
    // tray list; per tray, "n" is the emptiness sentinel (falsy/missing =
    // empty). tray_color is RRGGBBAA (6-hex color + 2-hex alpha).
    private fun JsonObject.amsTraysOrNull(): List<AMSTray>? {
        val amsBlock = this["ams"]?.jsonObject ?: return null
        val existBits = amsBlock.stringField("ams_exist_bits")
        if (existBits == "0") return null
        val units = amsBlock["ams"] as? JsonArray ?: return null
        val unit = units.firstOrNull()?.jsonObject ?: return null
        val trayList = unit["tray"] as? JsonArray ?: return null
        val activeIndex = amsBlock.intField("tray_now")?.takeIf { it in 0 until 4 }

        return (0 until 4).map { i ->
            val tray = trayList.getOrNull(i)?.jsonObject
            val occupied = tray?.get("n")?.let { it.jsonPrimitive.booleanOrNull ?: it.jsonPrimitive.contentOrNull?.isNotBlank() } == true
            if (tray == null || !occupied) {
                AMSTray(slotIndex = i, isEmpty = true)
            } else {
                AMSTray(
                    slotIndex = i,
                    filamentType = tray.stringField("tray_type"),
                    subBrand = tray.stringField("tray_sub_brands")?.ifBlank { null },
                    colorHex = normalizeColor(tray.stringField("tray_color")),
                    isActive = i == activeIndex,
                    isEmpty = false,
                )
            }
        }
    }

    // tray_tar is the AMS's current target slot, tray_now is what's
    // actually active -- they differ while a switch (load/unload, or one
    // triggered internally by project_file/ams_change_filament) is
    // physically in progress and match once it settles. Confirmed live
    // against this exact field pair while building the AMS pre-load fix
    // (see RealBackend.ensureTrayLoaded). null (not false) means "can't
    // tell" -- e.g. a partial update missing this block entirely.
    private fun JsonObject.isAmsBusy(): Boolean? {
        val amsBlock = this["ams"]?.jsonObject ?: return null
        val trayTar = amsBlock.stringField("tray_tar") ?: return null
        val trayNow = amsBlock.stringField("tray_now") ?: return null
        return trayTar != trayNow
    }

    // AMS unit's own sensor readings (not per-tray), at ams.ams[0].temp /
    // .humidity_raw -- confirmed present on this printer's live telemetry
    // (e.g. temp=34.4, humidity_raw=40, read as ~40% RH). "humidity" (a
    // coarse 1-5 level, distinct from humidity_raw) is deliberately not
    // surfaced -- its exact meaning wasn't confirmed live, unlike
    // humidity_raw's plain percent shape.
    private fun JsonObject.amsUnit(): JsonObject? {
        val amsBlock = this["ams"]?.jsonObject ?: return null
        val units = amsBlock["ams"] as? JsonArray ?: return null
        return units.firstOrNull()?.jsonObject
    }

    private fun JsonObject.amsUnitTemp(): Double? = amsUnit()?.doubleField("temp")

    private fun JsonObject.amsUnitHumidityPercent(): Int? = amsUnit()?.doubleField("humidity_raw")?.toInt()

    // tray_color arrives as 8-hex RRGGBBAA (alpha suffix, no '#'); a 3MF's
    // slice_info.config records filament color as "#RRGGBB" -- normalizing
    // both to the same "#RRGGBB" shape here is what lets AmsMapping's exact
    // type+color match actually succeed. Without this every real print fell
    // through to the color-unconfirmed/no-material path and needed the
    // mismatch dialog even for an exact match -- found live, port of the
    // Python app's real_backend.py _normalize_color.
    private fun normalizeColor(trayColor: String?): String? {
        val stripped = trayColor?.removePrefix("#") ?: return null
        if (stripped.length < 6) return null
        return "#${stripped.substring(0, 6).uppercase()}"
    }

    private fun JsonObject.hmsEntriesOrNull(hmsCodes: HmsCodes): List<String>? {
        val entries = this["hms"] as? JsonArray ?: return null
        return entries.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val attr = obj.intField("attr") ?: return@mapNotNull null
            val code = obj.intField("code") ?: return@mapNotNull null
            hmsCodes.describe(attr, code)
        }
    }

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.doubleField(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.intField(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull
}
