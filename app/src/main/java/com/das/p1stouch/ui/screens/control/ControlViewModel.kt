package com.das.p1stouch.ui.screens.control

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.das.p1stouch.printer.PrinterBackend
import com.das.p1stouch.state.PrinterState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ControlViewModel(private val backend: PrinterBackend) : ViewModel() {
    val state: StateFlow<PrinterState> = backend.state.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), PrinterState(),
    )

    fun home() = launch { backend.homeAxes() }
    fun jog(axis: Char, distanceMm: Double) = launch { backend.jog(axis, distanceMm) }
    fun extrude(mm: Double) = launch { backend.extrude(mm) }
    fun setNozzleTarget(celsius: Int) = launch { backend.setNozzleTarget(celsius) }
    fun setBedTarget(celsius: Int) = launch { backend.setBedTarget(celsius) }
    fun setFanSpeed(fan: String, percent: Int) = launch { backend.setFanSpeed(fan, percent) }
    fun setLight(on: Boolean) = launch { if (on) backend.lightOn() else backend.lightOff() }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
