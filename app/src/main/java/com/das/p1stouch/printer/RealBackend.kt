package com.das.p1stouch.printer

import android.graphics.Bitmap
import android.util.Log
import com.das.p1stouch.printer.mqtt.MqttPrinterClient
import com.das.p1stouch.printer.mqtt.PrinterCommands
import com.das.p1stouch.printer.mqtt.PrinterTelemetry
import com.das.p1stouch.state.ConnectionState
import com.das.p1stouch.state.PrintFile
import com.das.p1stouch.state.PrinterState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Backend that talks to a real P1S over the local network. MQTT-only for
 * now (M3 of the approved plan) -- FTP (file list/thumbnails, M4) and the
 * camera stream (M5) are still stubs, matching the Python app's own
 * build-mock-first-then-real-hardware-one-piece-at-a-time discipline.
 */
class RealBackend(
    ip: String,
    accessCode: String,
    serial: String,
) : PrinterBackend {
    private val scope = CoroutineScope(SupervisorJob())
    private val mqttClient = MqttPrinterClient(ip, accessCode, serial)

    private val _state = MutableStateFlow(PrinterState())
    override val state: StateFlow<PrinterState> = _state.asStateFlow()

    private val _cameraFrames = MutableSharedFlow<Bitmap>(extraBufferCapacity = 1)
    override val cameraFrames: SharedFlow<Bitmap> = _cameraFrames.asSharedFlow()

    private val _fileList = MutableStateFlow<List<PrintFile>>(emptyList())
    override val fileList: StateFlow<List<PrintFile>> = _fileList.asStateFlow()

    private val _thumbnails = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    override val thumbnails: StateFlow<Map<String, Bitmap>> = _thumbnails.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val errors: SharedFlow<String> = _errors.asSharedFlow()

    private var reportJob: Job? = null
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null
    private var pushAllRefreshJob: Job? = null
    private var wantsConnection = false

    // -- lifecycle -----------------------------------------------------
    override suspend fun connect() {
        wantsConnection = true
        _state.update { it.copy(connection = ConnectionState.CONNECTING) }
        attemptConnect()
        startWatchdog()
        startPushAllRefresh()
    }

    override suspend fun disconnect() {
        wantsConnection = false
        watchdogJob?.cancel(); watchdogJob = null
        pushAllRefreshJob?.cancel(); pushAllRefreshJob = null
        reportJob?.cancel(); reportJob = null
        reconnectJob?.cancel(); reconnectJob = null
        mqttClient.disconnect()
        _state.update { it.copy(connection = ConnectionState.DISCONNECTED) }
    }

    // Mirrors bambulabs_api's pushall_timeout (default 60s): the printer
    // doesn't keep streaming full telemetry forever off one request, so
    // periodically ask again while connected.
    private fun startPushAllRefresh() {
        pushAllRefreshJob?.cancel()
        pushAllRefreshJob = scope.launch {
            while (isActive) {
                delay(PUSHALL_REFRESH_MS)
                if (_state.value.connection == ConnectionState.CONNECTED) {
                    publishSafely(PrinterCommands.pushAll())
                }
            }
        }
    }

    private suspend fun attemptConnect() {
        try {
            mqttClient.connect()
            _state.update { it.copy(connection = ConnectionState.CONNECTED) }
            reportJob?.cancel()
            reportJob = scope.launch {
                mqttClient.subscribeReports()
                    .catch { e ->
                        Log.w(TAG, "report subscription failed", e)
                        _errors.emit("Connection lost: ${e.message}")
                        onUnexpectedDisconnect()
                    }
                    .collect { payload ->
                        _state.update { prev -> PrinterTelemetry.parse(payload, prev) }
                    }
            }
            // The printer only streams full telemetry after this is
            // requested (see PrinterCommands.pushAll) -- a brief delay gives
            // the subscribe above time to actually take effect (SUBACK)
            // before asking, so the response isn't missed.
            delay(SUBSCRIBE_SETTLE_MS)
            publishSafely(PrinterCommands.pushAll())
        } catch (e: Exception) {
            Log.w(TAG, "connect failed", e)
            _errors.emit("Connection failed: ${e.message}")
            _state.update { it.copy(connection = ConnectionState.RECONNECTING) }
            scheduleReconnect()
        }
    }

    private fun onUnexpectedDisconnect() {
        Log.w(TAG, "unexpected disconnect (wantsConnection=$wantsConnection)")
        if (!wantsConnection) return
        _state.update { it.copy(connection = ConnectionState.RECONNECTING) }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (wantsConnection) attemptConnect()
        }
    }

    // HiveMQ's connection-state callbacks aren't wired here (kept the
    // reconnect path simple/explicit rather than guessing at that API
    // surface) -- this poll is the fallback net that catches a silently
    // dropped socket the report-Flow's own error path didn't see.
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                if (wantsConnection && !mqttClient.isConnected && _state.value.connection == ConnectionState.CONNECTED) {
                    onUnexpectedDisconnect()
                }
            }
        }
    }

    // -- print job control ------------------------------------------------
    override suspend fun startPrint(path: String, plate: Int) {
        // TODO(M4): needs the FTP-listed file path + "file:///sdcard/..."
        // project_file command -- not wired until Print Files (M4) exists.
        _errors.emit("Starting prints isn't wired up yet")
    }

    override suspend fun pausePrint() = publishSafely(PrinterCommands.pausePrint())
    override suspend fun resumePrint() = publishSafely(PrinterCommands.resumePrint())
    override suspend fun stopPrint() = publishSafely(PrinterCommands.stopPrint())
    override suspend fun setSpeedLevel(level: Int) = publishSafely(PrinterCommands.setSpeedLevel(level))

    // -- temperature / motion --------------------------------------------
    override suspend fun setNozzleTarget(celsius: Int) = publishSafely(PrinterCommands.setNozzleTarget(celsius))
    override suspend fun setBedTarget(celsius: Int) = publishSafely(PrinterCommands.setBedTarget(celsius))
    override suspend fun homeAxes() = publishSafely(PrinterCommands.homeAxes())
    override suspend fun jog(axis: Char, distanceMm: Double) = publishSafely(PrinterCommands.jog(axis, distanceMm))
    override suspend fun extrude(mm: Double) = publishSafely(PrinterCommands.extrude(mm))

    override suspend fun setFanSpeed(fan: String, percent: Int) {
        val command = PrinterCommands.setFanSpeed(fan, percent) ?: return
        publishSafely(command)
    }

    override suspend fun lightOn() = publishSafely(PrinterCommands.lightOn())
    override suspend fun lightOff() = publishSafely(PrinterCommands.lightOff())

    // -- AMS ----------------------------------------------------------------
    override suspend fun loadFilament(slot: Int) {
        val currentTemp = _state.value.nozzleTemp?.toInt() ?: 0
        publishSafely(PrinterCommands.amsChangeFilament(slot, currentTemp, DEFAULT_NOZZLE_TARGET_FOR_SWAP))
    }

    override suspend fun unloadFilament(slot: Int) = publishSafely(PrinterCommands.unloadFilament())

    // -- files ----------------------------------------------------------------
    override suspend fun requestFileList() {
        // TODO(M4): FTP wiring.
        _fileList.value = emptyList()
    }

    // -- helpers ----------------------------------------------------------------
    private suspend fun publishSafely(payload: JsonObject) {
        try {
            mqttClient.publish(payload.toString())
        } catch (e: Exception) {
            Log.w(TAG, "publish failed: $payload", e)
            _errors.emit("Command failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "RealBackend"
        private const val RECONNECT_DELAY_MS = 5000L
        private const val WATCHDOG_INTERVAL_MS = 5000L
        private const val SUBSCRIBE_SETTLE_MS = 500L
        private const val PUSHALL_REFRESH_MS = 50_000L
        private const val DEFAULT_NOZZLE_TARGET_FOR_SWAP = 220
    }
}
