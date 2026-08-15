package com.das.p1stouch.printer

import android.graphics.Bitmap
import com.das.p1stouch.state.PrintFile
import com.das.p1stouch.state.PrinterState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Backend interface shared by the mock and real printer backends. UI code
 * (Compose screens / ViewModels) talks only to this interface and never
 * touches MQTT/FTP/camera clients directly -- swapping backends is a
 * one-line change in [com.das.p1stouch.App]. One-to-one port of the Python
 * app's printer/base.py.
 */
interface PrinterBackend {
    val state: StateFlow<PrinterState>
    val cameraFrames: SharedFlow<Bitmap>
    val fileList: StateFlow<List<PrintFile>>
    val thumbnails: StateFlow<Map<String, Bitmap>>
    val errors: SharedFlow<String>

    // A specific, actionable reason a resolved print hasn't actually
    // started yet (e.g. an uncertain AMS filament match) -- the UI must
    // resolve it via confirmPendingPrint()/cancelPendingPrint(), shown as
    // a dialog the user has to actively choose on rather than a snackbar
    // that could be missed.
    val printStartWarnings: SharedFlow<String>

    // -- lifecycle --------------------------------------------------------
    suspend fun connect()
    suspend fun disconnect()

    // -- print job control --------------------------------------------------
    suspend fun startPrint(path: String, plate: Int = 1)

    // No-ops unless a printStartWarnings event is currently pending --
    // confirm actually starts the print, cancel abandons it.
    suspend fun confirmPendingPrint()
    suspend fun cancelPendingPrint()

    suspend fun pausePrint()
    suspend fun resumePrint()
    suspend fun stopPrint()
    suspend fun setSpeedLevel(level: Int)

    // -- temperature / motion -----------------------------------------------
    suspend fun setNozzleTarget(celsius: Int)
    suspend fun setBedTarget(celsius: Int)
    suspend fun homeAxes()
    suspend fun jog(axis: Char, distanceMm: Double)
    suspend fun extrude(mm: Double) // negative mm = retract, no separate method
    suspend fun setFanSpeed(fan: String, percent: Int)
    suspend fun lightOn()
    suspend fun lightOff()

    // -- AMS / filament -------------------------------------------------------
    suspend fun loadFilament(slot: Int)
    suspend fun unloadFilament(slot: Int)

    // -- files ---------------------------------------------------------------
    suspend fun requestFileList()
}
