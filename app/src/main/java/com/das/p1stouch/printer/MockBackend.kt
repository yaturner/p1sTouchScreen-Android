package com.das.p1stouch.printer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.das.p1stouch.state.AMSTray
import com.das.p1stouch.state.ConnectionState
import com.das.p1stouch.state.GcodeState
import com.das.p1stouch.state.PrintFile
import com.das.p1stouch.state.PrinterState
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Synthetic backend for UI development without a printer. Exercises exactly
 * the same [PrinterBackend] surface as [com.das.p1stouch.printer.real.RealBackend]
 * so screens never need to know which backend is active. Port of the Python
 * app's printer/mock_backend.py.
 */
class MockBackend(private val scope: CoroutineScope) : PrinterBackend {

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

    // Never emitted -- the mock has no AMS-mismatch scenario to simulate,
    // matching the Python app's mock_backend.py no-op.
    private val _printStartWarnings = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val printStartWarnings: SharedFlow<String> = _printStartWarnings.asSharedFlow()

    private var tickJob: Job? = null
    private var cameraJob: Job? = null

    private val startTimeMs = System.currentTimeMillis()
    private var printStartedAtMs = 0L
    private var nozzleTarget = 0
    private var bedTarget = 0
    private var isLightOn = true
    private var speedLevel = 2
    private var printing = false
    private var currentFile: String? = null
    private var gcodeStateOverride: GcodeState? = null // set on pause/finish; cleared when a new print starts
    private val fanSpeeds = mutableMapOf("part" to 0, "aux" to 0, "chamber" to 0)
    private val amsTrays = mutableListOf(
        AMSTray(0, "PLA", "#1E88E5", isActive = true, isEmpty = false),
        AMSTray(1, "PETG", "#43A047", isActive = false, isEmpty = false),
        AMSTray(2, "PLA", "#FDD835", isActive = false, isEmpty = false),
        AMSTray(3, "ABS", "#E53935", isActive = false, isEmpty = false),
    )

    // -- lifecycle -----------------------------------------------------
    override suspend fun connect() {
        _state.update { it.copy(connection = ConnectionState.CONNECTING) }
        _state.update { it.copy(connection = ConnectionState.CONNECTED) }
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                tick()
                delay(1000)
            }
        }
        cameraJob?.cancel()
        cameraJob = scope.launch {
            while (isActive) {
                _cameraFrames.emit(fakeFrame())
                delay(500)
            }
        }
    }

    override suspend fun disconnect() {
        tickJob?.cancel(); tickJob = null
        cameraJob?.cancel(); cameraJob = null
        _state.update { it.copy(connection = ConnectionState.DISCONNECTED) }
    }

    // -- print job control ------------------------------------------------
    override suspend fun startPrint(path: String, plate: Int) {
        printing = true
        printStartedAtMs = System.currentTimeMillis()
        currentFile = path.substringAfterLast('/')
        gcodeStateOverride = null
        tick()
    }

    override suspend fun confirmPendingPrint() {
        // no-op: mock never holds a pending print
    }

    override suspend fun cancelPendingPrint() {
        // no-op: mock never holds a pending print
    }

    override suspend fun pausePrint() {
        gcodeStateOverride = GcodeState.PAUSE
        tick()
    }

    override suspend fun resumePrint() {
        gcodeStateOverride = null
        tick()
    }

    override suspend fun stopPrint() {
        printing = false
        currentFile = null
        gcodeStateOverride = null
        _state.update { it.copy(gcodeState = GcodeState.IDLE, printPercent = null, currentLayer = null, currentFile = null) }
    }

    override suspend fun setSpeedLevel(level: Int) {
        speedLevel = level
        tick()
    }

    // -- temperature / motion --------------------------------------------
    override suspend fun setNozzleTarget(celsius: Int) {
        nozzleTarget = celsius
        tick()
    }

    override suspend fun setBedTarget(celsius: Int) {
        bedTarget = celsius
        tick()
    }

    override suspend fun homeAxes() {
        // no-op in mock; RealBackend sends G28
    }

    override suspend fun jog(axis: Char, distanceMm: Double) {
        // no physical motion to simulate meaningfully
    }

    override suspend fun extrude(mm: Double) {
        // no-op in mock
    }

    override suspend fun setFanSpeed(fan: String, percent: Int) {
        fanSpeeds[fan] = percent
        tick()
    }

    override suspend fun lightOn() {
        isLightOn = true
        tick()
    }

    override suspend fun lightOff() {
        isLightOn = false
        tick()
    }

    // -- AMS ----------------------------------------------------------------
    override suspend fun loadFilament(slot: Int) {
        for (i in amsTrays.indices) {
            amsTrays[i] = amsTrays[i].copy(isActive = amsTrays[i].slotIndex == slot)
        }
        tick()
    }

    override suspend fun unloadFilament(slot: Int) {
        val idx = amsTrays.indexOfFirst { it.slotIndex == slot }
        if (idx >= 0) amsTrays[idx] = amsTrays[idx].copy(isActive = false)
        tick()
    }

    // -- files ----------------------------------------------------------------
    override suspend fun requestFileList() {
        _fileList.value = MOCK_FILES
        // Fake the real backend's "thumbnails pop in a bit after the list"
        // behavior (there it's a background download; here it's a
        // deliberate delay) so incremental-icon-loading gets exercised too.
        scope.launch {
            MOCK_FILES.forEachIndexed { i, f ->
                delay(400L + i * 250L)
                val color = MOCK_AMS_COLORS[f.path.hashCode().mod(MOCK_AMS_COLORS.size)]
                val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                Canvas(bmp).drawColor(Color.parseColor(color))
                _thumbnails.update { it + (f.path to bmp) }
            }
        }
    }

    // -- internal ----------------------------------------------------------
    private fun tick() {
        val elapsedSec = (System.currentTimeMillis() - startTimeMs) / 1000.0
        val nozzle = if (nozzleTarget != 0) {
            nozzleTarget * (1 - exp(-elapsedSec / 20))
        } else {
            25 + 2 * sin(elapsedSec / 5)
        }
        val bed = if (bedTarget != 0) {
            bedTarget * (1 - exp(-elapsedSec / 30))
        } else {
            24 + sin(elapsedSec / 7)
        }

        var gcodeState = GcodeState.IDLE
        var printPercent: Int? = null
        var currentLayer: Int? = null
        var totalLayer: Int? = null
        var remainingMinutes: Int? = null

        if (printing) {
            val printElapsedSec = (System.currentTimeMillis() - printStartedAtMs) / 1000.0
            val totalSeconds = 600.0 // fake 10-minute print
            val pct = min(100, (printElapsedSec / totalSeconds * 100).toInt())
            gcodeState = gcodeStateOverride ?: GcodeState.RUNNING
            printPercent = pct
            totalLayer = 120
            currentLayer = min(120, (pct / 100.0 * 120).toInt())
            remainingMinutes = maxOf(0, ((totalSeconds - printElapsedSec) / 60).toInt())
            if (pct >= 100) {
                gcodeState = GcodeState.FINISH
                printing = false
            }
        } else if (gcodeStateOverride == GcodeState.PAUSE) {
            gcodeState = GcodeState.PAUSE
        }

        _state.update {
            it.copy(
                connection = ConnectionState.CONNECTED,
                nozzleTemp = round1(nozzle),
                nozzleTarget = nozzleTarget.toDouble(),
                bedTemp = round1(bed),
                bedTarget = bedTarget.toDouble(),
                chamberTemp = 28.0,
                lightOn = isLightOn,
                speedLevel = speedLevel,
                fanSpeeds = fanSpeeds.toMap(),
                amsTrays = amsTrays.toList(),
                hmsErrors = emptyList(),
                gcodeState = gcodeState,
                printPercent = printPercent,
                currentLayer = currentLayer,
                totalLayer = totalLayer,
                remainingMinutes = remainingMinutes,
                currentFile = if (printing || gcodeStateOverride == GcodeState.PAUSE) currentFile else null,
            )
        }
    }

    private fun fakeFrame(): Bitmap {
        val bmp = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val hue = ((System.currentTimeMillis() / 25) % 360).toFloat()
        canvas.drawColor(Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.35f)))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 28f
        }
        val label = if (printing) "PRINTING" else "MOCK CAMERA"
        canvas.drawText(label, bmp.width / 2f, bmp.height / 2f, paint)
        return bmp
    }

    private fun round1(v: Double) = Math.round(v * 10) / 10.0

    companion object {
        private val MOCK_FILES = listOf(
            PrintFile(name = "benchy.gcode.3mf", path = "cache/benchy.gcode.3mf", sizeBytes = 1_200_000),
            PrintFile(name = "calibration_cube.3mf", path = "cache/calibration_cube.3mf", sizeBytes = 340_000),
            PrintFile(name = "bracket_v3.gcode.3mf", path = "cache/bracket_v3.gcode.3mf", sizeBytes = 980_000),
        )
        private val MOCK_AMS_COLORS = listOf("#1E88E5", "#43A047", "#FDD835", "#E53935")
    }
}
