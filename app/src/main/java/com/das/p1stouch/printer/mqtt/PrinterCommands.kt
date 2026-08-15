package com.das.p1stouch.printer.mqtt

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builders for outgoing command JSON published to device/{serial}/request.
 * Every shape here was read directly out of bambulabs_api's mqtt_client.py
 * (not guessed/documented secondhand) -- see the comments on each command
 * for the exact source method it mirrors.
 */
object PrinterCommands {

    // mqtt_client.py's __send_gcode_line(): {"print": {"sequence_id": "0",
    // "command": "gcode_line", "param": "<gcode>\n"}}. sequence_id is always
    // literal "0" for every gcode_line call in the source, not incrementing.
    private fun gcodeLine(gcode: String): JsonObject = buildJsonObject {
        putJsonObject("print") {
            put("sequence_id", "0")
            put("command", "gcode_line")
            put("param", "$gcode\n")
        }
    }

    // auto_home(): gcode "G28\n"
    fun homeAxes(): JsonObject = gcodeLine("G28")

    // real_backend.py's jog(): relative move, G91/G1<axis><±mm> F<feed>/G90.
    // feed 600 for Z (slower), 3000 for X/Y. No dedicated relative-jog method
    // in bambulabs_api -- this is the app's own g-code sequence, not a
    // library-provided command.
    fun jog(axis: Char, distanceMm: Double): JsonObject {
        val feed = if (axis == 'Z') 600 else 3000
        return gcodeLine("G91\nG1 ${axis}${formatNum(distanceMm)} F$feed\nG90")
    }

    // real_backend.py's extrude(): M83 (relative extrusion) then G1 E<mm>.
    // Negative mm retracts -- no separate retract command.
    fun extrude(mm: Double): JsonObject = gcodeLine("M83\nG1 E${formatNum(mm)} F300")

    // set_nozzle_temperature()/set_bed_temperature(): gcode M104/M140 (fire
    // and forget -- newer firmware's M109/M190 wait-for-temp variant is
    // deliberately not used here so the UI doesn't block on a slow heat-up).
    fun setNozzleTarget(celsius: Int): JsonObject = gcodeLine("M104 S$celsius")
    fun setBedTarget(celsius: Int): JsonObject = gcodeLine("M140 S$celsius")

    // _set_fan_speed(): M106 P<fan_num> S<speed>, speed = raw PWM 0-255 (not
    // percent). fan_num: 1=part, 2=aux, 3=chamber.
    fun setFanSpeed(fan: String, percent: Int): JsonObject? {
        val fanNum = when (fan) {
            "part" -> 1
            "aux" -> 2
            "chamber" -> 3
            else -> return null
        }
        val clamped = percent.coerceIn(0, 100)
        val rawPwm = Math.round(clamped / 100.0 * 255).toInt()
        return gcodeLine("M106 P$fanNum S$rawPwm")
    }

    // turn_light_on()/turn_light_off(): {"system": {"led_mode": "on"/"off"}}
    fun lightOn(): JsonObject = buildJsonObject { putJsonObject("system") { put("led_mode", "on") } }
    fun lightOff(): JsonObject = buildJsonObject { putJsonObject("system") { put("led_mode", "off") } }

    // mqtt_client.py's pushall(): {"pushing": {"command": "pushall"}}. The
    // printer only streams FULL telemetry after this is requested --
    // without it we saw only sparse, spontaneous partial fields (e.g. a lone
    // bed_temper reading with no nozzle data at all) on a live P1S. Sent
    // once right after connect (bambulabs_api's pushall_on_connect=True
    // default) and re-sent periodically as a keepalive/refresh
    // (pushall_timeout, default 60s there).
    fun pushAll(): JsonObject = buildJsonObject { putJsonObject("pushing") { put("command", "pushall") } }

    // pause_print()/resume_print()/stop_print()
    fun pausePrint(): JsonObject = buildJsonObject { putJsonObject("print") { put("command", "pause") } }
    fun resumePrint(): JsonObject = buildJsonObject { putJsonObject("print") { put("command", "resume") } }
    fun stopPrint(): JsonObject = buildJsonObject { putJsonObject("print") { put("command", "stop") } }

    // set_print_speed_lvl(): {"print": {"command": "print_speed", "param": "<1-4>"}}
    // -- param is a STRING, not an int.
    fun setSpeedLevel(level: Int): JsonObject = buildJsonObject {
        putJsonObject("print") {
            put("command", "print_speed")
            put("param", level.coerceIn(1, 4).toString())
        }
    }

    // real_backend.py's own AMS commands (not in bambulabs_api's high-level
    // API at all -- confirmed against OpenBambuAPI's documented schema and
    // live-tested on a real P1S + AMS).
    fun amsChangeFilament(slot: Int, currentTempC: Int, targetTempC: Int): JsonObject = buildJsonObject {
        putJsonObject("print") {
            put("sequence_id", "0")
            put("command", "ams_change_filament")
            put("target", slot)
            put("curr_temp", currentTempC)
            put("tar_temp", targetTempC)
        }
    }

    fun unloadFilament(): JsonObject = buildJsonObject {
        putJsonObject("print") {
            put("sequence_id", "0")
            put("command", "unload_filament")
        }
    }

    // real_backend.py's start_print()/_start_print_attempt(): "project_file"
    // command. The critical, live-tested detail is the url scheme --
    // bambulabs_api's own default ("ftp:///{filename}") reliably fails to
    // actually start the print on this printer (stuck IDLE/FAILED, no
    // heating); "file:///sdcard/<path>" (the file's own local SD path) is
    // what actually works. use_ams=false caused a DIFFERENT real failure
    // ("External filament is missing" -- feeds from the nonexistent
    // external spool holder), so use_ams is always true for an
    // AMS-equipped printer; amsMapping is resolved per-file by
    // RealBackend.resolveAmsMapping() rather than hardcoded to [0], which
    // is what caused HMS_0700_7000_0002_0008 ("failed to get AMS mapping
    // table") whenever slot 0 didn't happen to hold the filament the file
    // actually wants (confirmed live -- see the matching fix in the Python
    // app, ported here).
    fun startPrint(path: String, plate: Int, amsMapping: List<Int> = listOf(0)): JsonObject {
        val bareName = path.substringAfterLast('/')
        return buildJsonObject {
            putJsonObject("print") {
                put("sequence_id", "2000")
                put("command", "project_file")
                put("param", "Metadata/plate_$plate.gcode")
                put("subtask_name", bareName)
                put("file", bareName)
                put("url", "file:///sdcard/$path")
                put("timelapse", false)
                put("bed_leveling", true)
                put("bed_type", "textured_plate")
                put("flow_cali", true)
                put("vibration_cali", true)
                put("layer_inspect", false)
                put("use_ams", true)
                put("ams_mapping", buildJsonArray { amsMapping.forEach { add(it) } })
            }
        }
    }

    // Kotlin's Double.toString() renders whole numbers as "5.0" -- gcode
    // accepts that fine, but "5" reads more naturally and matches what a
    // human would type; drop the trailing ".0" only, keep real decimals.
    private fun formatNum(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite()) v.toInt().toString() else v.toString()
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonObject(
    key: String,
    block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
) {
    put(key, buildJsonObject(block))
}
