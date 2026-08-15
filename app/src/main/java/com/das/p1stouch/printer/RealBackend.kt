package com.das.p1stouch.printer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.das.p1stouch.printer.ftp.FtpPrinterClient
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
 * Backend that talks to a real P1S over the local network: MQTT (M3) +
 * FTP file list/thumbnails (M4). The camera stream (M5) is still a stub,
 * matching the Python app's own build-mock-first-then-real-hardware-
 * one-piece-at-a-time discipline.
 */
class RealBackend(
    ip: String,
    accessCode: String,
    serial: String,
    cacheDir: File,
    private val skipThumbnails: Boolean = false,
) : PrinterBackend {
    private val scope = CoroutineScope(SupervisorJob())
    private val mqttClient = MqttPrinterClient(ip, accessCode, serial)
    private val ftpClient = FtpPrinterClient(ip, accessCode)
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
        startThumbnailWorker()
    }

    override suspend fun disconnect() {
        wantsConnection = false
        watchdogJob?.cancel(); watchdogJob = null
        pushAllRefreshJob?.cancel(); pushAllRefreshJob = null
        thumbnailWorkerJob?.cancel(); thumbnailWorkerJob = null
        reportJob?.cancel(); reportJob = null
        reconnectJob?.cancel(); reconnectJob = null
        mqttClient.disconnect()
        ftpClient.disconnect()
        _state.update { it.copy(connection = ConnectionState.DISCONNECTED) }
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
    override suspend fun startPrint(path: String, plate: Int) = publishSafely(PrinterCommands.startPrint(path, plate))

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
        // 3MF files above this size aren't worth a full download just for
        // the embedded preview PNG -- no cheap partial-fetch of one zip
        // member over this printer's FTP server.
        private const val MAX_THUMBNAIL_FILE_BYTES = 15L * 1024 * 1024
    }
}
