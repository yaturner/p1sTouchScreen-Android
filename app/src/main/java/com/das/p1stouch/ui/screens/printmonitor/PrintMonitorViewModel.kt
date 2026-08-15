package com.das.p1stouch.ui.screens.printmonitor

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.das.p1stouch.printer.PrinterBackend
import com.das.p1stouch.state.GcodeState
import com.das.p1stouch.state.PrinterState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PrintMonitorViewModel(private val backend: PrinterBackend) : ViewModel() {
    val state: StateFlow<PrinterState> = backend.state.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), PrinterState(),
    )

    // Widened to a nullable Bitmap? flow (SharedFlow<Bitmap> can't itself
    // supply a null "no frame yet" initial value for stateIn).
    val cameraFrame: StateFlow<Bitmap?> = backend.cameraFrames
        .map<Bitmap, Bitmap?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun pauseOrResume() {
        viewModelScope.launch {
            if (state.value.gcodeState == GcodeState.PAUSE) backend.resumePrint() else backend.pausePrint()
        }
    }

    fun stop() = viewModelScope.launch { backend.stopPrint() }
    fun setSpeedLevel(level: Int) = viewModelScope.launch { backend.setSpeedLevel(level) }
}
