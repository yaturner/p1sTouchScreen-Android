package com.das.p1stouch.state

/** Normalized printer/app state -- the only contract the printer/ backend layer
 * and the ui/ layer share. UI code must never read raw MQTT/FTP data directly,
 * only these types, delivered via [com.das.p1stouch.printer.PrinterBackend]'s flows. */

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING,
}

enum class GcodeState {
    IDLE, RUNNING, PAUSE, FINISH, FAILED, UNKNOWN,
}

data class AMSTray(
    val slotIndex: Int,
    val filamentType: String? = null,
    // The RFID's specific product name (e.g. "PLA Translucent"), distinct
    // from filamentType's generic material category (e.g. "PLA") -- from
    // raw tray_sub_brands. Null/blank for a manually-Edited slot, since
    // ams_filament_setting doesn't set this field.
    val subBrand: String? = null,
    val colorHex: String? = null,
    val isActive: Boolean = false,
    val isEmpty: Boolean = true,
)

data class PrintFile(
    val name: String,
    val path: String,
    val sizeBytes: Long? = null,
    val modifiedEpochMillis: Long? = null,
)

data class PrinterState(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,

    val nozzleTemp: Double? = null,
    val nozzleTarget: Double? = null,
    val bedTemp: Double? = null,
    val bedTarget: Double? = null,
    val chamberTemp: Double? = null,

    val gcodeState: GcodeState = GcodeState.UNKNOWN,
    val printPercent: Int? = null,
    val currentLayer: Int? = null,
    val totalLayer: Int? = null,
    val remainingMinutes: Int? = null,
    val currentFile: String? = null,
    val speedLevel: Int? = null, // 1=Silent .. 4=Ludicrous

    val fanSpeeds: Map<String, Int> = emptyMap(), // percent 0-100, keyed "part"/"aux"/"chamber"
    val lightOn: Boolean? = null,

    val amsTrays: List<AMSTray> = emptyList(),
    // True while the AMS is actively switching trays (a load/unload/print-
    // triggered swap is in flight) -- see PrinterTelemetry.isAmsBusy(). The
    // UI disables Load/Unload/Sync while true, since firing a second swap
    // mid-swap is untested territory.
    val amsBusy: Boolean = false,
    // AMS unit's own internal sensor readings (not per-tray) -- "temp" and
    // "humidity_raw" from ams.ams[0], confirmed present on this printer's
    // live telemetry (e.g. temp=34.4, humidity_raw=40 meaning ~40% RH).
    val amsTemp: Double? = null,
    val amsHumidityPercent: Int? = null,
    val hmsErrors: List<String> = emptyList(),
) {
    val isPrinting: Boolean
        get() = gcodeState == GcodeState.RUNNING || gcodeState == GcodeState.PAUSE
}
