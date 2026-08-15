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
    val hmsErrors: List<String> = emptyList(),
) {
    val isPrinting: Boolean
        get() = gcodeState == GcodeState.RUNNING || gcodeState == GcodeState.PAUSE
}
