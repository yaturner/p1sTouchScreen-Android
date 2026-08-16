package com.das.p1stouch.printer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.das.p1stouch.printer.camera.CameraStreamClient
import com.das.p1stouch.printer.ftp.FtpPrinterClient
import com.das.p1stouch.printer.hms.HmsCodes
import com.das.p1stouch.printer.mqtt.MqttPrinterClient
import com.das.p1stouch.printer.mqtt.PrinterCommands
import com.das.p1stouch.printer.mqtt.PrinterTelemetry
import com.das.p1stouch.state.ConnectionState
import com.das.p1stouch.state.PrintFile
import com.das.p1stouch.state.PrinterState
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Backend that talks to a real P1S over the local network: MQTT (M3),
 * FTP file list/thumbnails (M4), and the camera stream (M5).
 */
class RealBackend(
    ip: String,
    accessCode: String,
    serial: String,
    cacheDir: File,
    private val skipThumbnails: Boolean = false,
    private val hmsCodes: HmsCodes,
) : PrinterBackend {
    private val scope = CoroutineScope(SupervisorJob())
    private val mqttClient = MqttPrinterClient(ip, accessCode, serial)
    private val ftpClient = FtpPrinterClient(ip, accessCode)
    private val cameraClient = CameraStreamClient(ip, accessCode)
    private val thumbnailCacheDir = File(cacheDir, "thumbnails").apply { mkdirs() }
    // One at a time, not a coroutine-per-file fan-out: firing dozens of
    // concurrent download attempts (even mutex-serialized down to one FTP
    // operation at a time) hammered the printer hard enough to start
    // failing unrelated MQTT connections too, confirmed live against a
    // real P1S with ~60 cached files. Port of the Python app's
    // ThumbnailLoader -- one dedicated worker draining a queue.
    private val thumbnailQueue = Channel<Pair<String, File>>(Channel.UNLIMITED)
    private var thumbnailWorkerJob: Job? = null

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

    private val _printStartWarnings = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val printStartWarnings: SharedFlow<String> = _printStartWarnings.asSharedFlow()

    // A print resolved (path/plate/ams_mapping) but held back pending the
    // user's Print Anyway/Cancel choice -- see startPrint()/
    // confirmPendingPrint()/cancelPendingPrint(). Only ever touched from
    // suspend functions the UI calls one at a time (never a background
    // fan-out), so a plain field is enough -- port of the Python app's
    // RealBackend._pending_print.
    private var pendingPrint: Triple<String, Int, List<Int>>? = null

    private var reportJob: Job? = null
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null
    private var pushAllRefreshJob: Job? = null
    private var cameraJob: Job? = null
    private var wantsConnection = false

    // -- lifecycle -----------------------------------------------------
    override suspend fun connect() {
        wantsConnection = true
        _state.update { it.copy(connection = ConnectionState.CONNECTING) }
        attemptConnect()
        startWatchdog()
        startPushAllRefresh()
        startThumbnailWorker()
        startCameraStream()
    }

    override suspend fun disconnect() {
        wantsConnection = false
        watchdogJob?.cancel(); watchdogJob = null
        pushAllRefreshJob?.cancel(); pushAllRefreshJob = null
        thumbnailWorkerJob?.cancel(); thumbnailWorkerJob = null
        cameraJob?.cancel(); cameraJob = null
        reportJob?.cancel(); reportJob = null
        reconnectJob?.cancel(); reconnectJob = null
        mqttClient.disconnect()
        ftpClient.disconnect()
        _state.update { it.copy(connection = ConnectionState.DISCONNECTED) }
    }

    // Runs continuously alongside MQTT/FTP while connected, same as the
    // Python app's CameraPollThread -- not gated on the Print Monitor
    // screen actually being visible, since PrinterBackend has no concept
    // of "which screen is active" (that would be a UI-layer concern this
    // interface deliberately doesn't know about).
    private fun startCameraStream() {
        if (cameraJob?.isActive == true) return
        cameraJob = scope.launch {
            cameraClient.frames().collect { jpegBytes ->
                val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                if (bitmap != null) _cameraFrames.emit(bitmap)
            }
        }
    }

    private fun startThumbnailWorker() {
        if (thumbnailWorkerJob?.isActive == true) return
        thumbnailWorkerJob = scope.launch {
            for ((path, cacheFile) in thumbnailQueue) {
                extractAndCacheThumbnail(path, cacheFile)
            }
        }
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
                        _state.update { prev -> PrinterTelemetry.parse(payload, prev, hmsCodes) }
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
    // Resolves ams_mapping from the file's actual filament requirements
    // (see AmsMapping) before starting -- a hardcoded [0] is what caused
    // HMS_0700_7000_0002_0008 whenever slot 0 didn't hold a matching
    // filament. A confident match starts immediately; anything else is
    // held as pendingPrint and surfaced via printStartWarnings so the UI
    // can ask the user, mirroring the Python app's _PrintStartWorker /
    // _on_print_start_resolved gating.
    override suspend fun startPrint(path: String, plate: Int) {
        val result = resolveAmsMappingForFile(path)
        if (result.warning != null) {
            pendingPrint = Triple(path, plate, result.mapping)
            _printStartWarnings.emit(result.warning)
            return
        }
        beginPrint(path, plate, result.mapping)
    }

    private suspend fun resolveAmsMappingForFile(path: String): AmsMappingResult {
        if (!path.lowercase().endsWith(".3mf")) return AmsMappingResult(listOf(0), null)
        val zipBytes = try {
            withContext(Dispatchers.IO) { ftpClient.downloadFile(path) }
        } catch (e: Exception) {
            Log.w(TAG, "AMS mapping download failed for $path", e)
            null
        } ?: return AmsMappingResult(listOf(0), null)
        val filaments = try {
            withContext(Dispatchers.IO) { AmsMapping.parseFilamentRequirements(zipBytes, thumbnailCacheDir) }
        } catch (e: Exception) {
            Log.w(TAG, "AMS mapping parse failed for $path", e)
            null
        } ?: return AmsMappingResult(listOf(0), null)
        return AmsMapping.resolveAmsMapping(filaments, _state.value.amsTrays)
    }

    override suspend fun confirmPendingPrint() {
        val (path, plate, mapping) = pendingPrint ?: return
        pendingPrint = null
        beginPrint(path, plate, mapping)
    }

    override suspend fun cancelPendingPrint() {
        pendingPrint = null
    }

    // project_file's inline ams_mapping does not reliably make the printer
    // feed filament into a completely empty/unloaded toolhead -- confirmed
    // live on the Python app's real printer (tray_now stayed unset
    // indefinitely, zero AMS motor activity, the print sat at RUNNING with
    // both heaters at target but layer/percent never moving). The same
    // ams_change_filament command loadFilament() already sends does
    // reliably trigger the swap, so explicitly request it and wait for the
    // AMS to actually report the target slot active before publishing
    // project_file, rather than trusting it to handle a cold load itself.
    private suspend fun beginPrint(path: String, plate: Int, mapping: List<Int>) {
        val targetSlot = mapping.firstOrNull()
        if (targetSlot != null && !ensureTrayLoaded(targetSlot)) {
            _errors.emit("Timed out waiting for the AMS to load the needed filament -- starting the print anyway.")
        }
        publishSafely(PrinterCommands.startPrint(path, plate, mapping))
    }

    private suspend fun ensureTrayLoaded(targetSlot: Int): Boolean {
        if (_state.value.amsTrays.firstOrNull { it.isActive }?.slotIndex == targetSlot) return true
        loadFilament(targetSlot)
        val deadline = System.currentTimeMillis() + AMS_LOAD_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(AMS_LOAD_POLL_MS)
            if (_state.value.amsTrays.firstOrNull { it.isActive }?.slotIndex == targetSlot) return true
        }
        return false
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

    // Same pushAll() request already sent on connect and periodically
    // (see startPushAllRefresh) -- no local command forces the AMS to
    // re-scan RFID, this just re-requests full state immediately instead
    // of waiting for the next periodic refresh.
    // Publishes directly (not via publishSafely) so a confirmation can be
    // shown on success too -- confirmed live that a silent syncAms() felt
    // broken even though it was actually publishing every tap (AMS data
    // is usually already fresh from the periodic refresh, so there's
    // rarely any visible change to prove it worked).
    override suspend fun syncAms() {
        try {
            mqttClient.publish(PrinterCommands.pushAll().toString())
            _errors.emit("AMS synced")
        } catch (e: Exception) {
            Log.w(TAG, "sync AMS failed", e)
            _errors.emit("Command failed: ${e.message}")
        }
    }

    override suspend fun setFilamentSettings(slot: Int, filamentKey: String, colorHex: String) {
        val preset = FilamentPresets.BY_KEY[filamentKey] ?: run {
            Log.w(TAG, "unknown filament preset $filamentKey")
            return
        }
        publishSafely(
            PrinterCommands.amsFilamentSetting(
                slot, preset.trayInfoIdx, colorHex, preset.nozzleTempMin, preset.nozzleTempMax, preset.trayType,
            ),
        )
    }

    override suspend fun runCalibration() = publishSafely(PrinterCommands.calibration())

    // -- files ----------------------------------------------------------------
    override suspend fun requestFileList() {
        val files = try {
            withContext(Dispatchers.IO) { ftpClient.listCacheDir() }
                .filter { entry ->
                    val lower = entry.name.lowercase()
                    lower.endsWith(".3mf") || lower.endsWith(".stl")
                }
                .map { entry ->
                    PrintFile(
                        name = entry.name,
                        path = "cache/${entry.name}",
                        sizeBytes = entry.sizeBytes,
                        modifiedEpochMillis = entry.modifiedEpochMillis,
                    )
                }
        } catch (e: Exception) {
            Log.w(TAG, "file list failed", e)
            _errors.emit("Couldn't list files: ${e.message}")
            _fileList.value = emptyList()
            return
        }
        _fileList.value = files
        if (skipThumbnails) return

        // Only .3mf files actually embed a thumbnail (a raw .stl has none);
        // files over the cap aren't worth a full download just for the
        // preview image.
        for (f in files) {
            if (!f.name.lowercase().endsWith(".3mf")) continue
            if ((f.sizeBytes ?: 0L) > MAX_THUMBNAIL_FILE_BYTES) continue
            val cacheFile = thumbnailCachePath(f.path, f.sizeBytes ?: 0L)
            val cached = loadCachedThumbnail(cacheFile)
            if (cached != null) {
                _thumbnails.update { it + (f.path to cached) }
            } else {
                thumbnailQueue.trySend(f.path to cacheFile)
            }
        }
    }

    private suspend fun extractAndCacheThumbnail(path: String, cacheFile: File) {
        val zipBytes = try {
            withContext(Dispatchers.IO) { ftpClient.downloadFile(path) }
        } catch (e: Exception) {
            Log.d(TAG, "thumbnail download failed for $path", e)
            null
        } ?: return
        val pngBytes = try {
            withContext(Dispatchers.IO) { ThumbnailExtractor.extractPlatePng(zipBytes, thumbnailCacheDir) }
        } catch (e: Exception) {
            Log.w(TAG, "thumbnail extraction threw for $path", e)
            null
        }
        if (pngBytes == null) {
            Log.w(TAG, "thumbnail extraction found no PNG for $path")
            return
        }
        withContext(Dispatchers.IO) {
            try {
                cacheFile.writeBytes(pngBytes)
            } catch (e: Exception) {
                Log.d(TAG, "failed to write thumbnail cache for $path", e)
            }
        }
        val bitmap = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size) ?: return
        _thumbnails.update { it + (path to bitmap) }
    }

    // Keyed on path+size rather than path alone: a same-named file re-sliced/
    // re-uploaded with different content will very likely differ in size
    // too, so this catches the common "file changed" case without needing
    // to parse the FTP LIST's date column.
    private fun thumbnailCachePath(path: String, sizeBytes: Long): File {
        val key = sha1("$path:$sizeBytes")
        return File(thumbnailCacheDir, "$key.png")
    }

    private fun loadCachedThumbnail(file: File): Bitmap? {
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    private fun sha1(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
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
        private const val AMS_LOAD_TIMEOUT_MS = 60_000L
        private const val AMS_LOAD_POLL_MS = 1500L
        // 3MF files above this size aren't worth a full download just for
        // the embedded preview PNG -- no cheap partial-fetch of one zip
        // member over this printer's FTP server.
        private const val MAX_THUMBNAIL_FILE_BYTES = 15L * 1024 * 1024
    }
}
