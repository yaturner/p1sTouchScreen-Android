package com.das.p1stouch.ui.screens.filamentams

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.das.p1stouch.printer.PrinterBackend
import com.das.p1stouch.state.PrinterState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FilamentAmsViewModel(private val backend: PrinterBackend) : ViewModel() {
    val state: StateFlow<PrinterState> = backend.state.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), PrinterState(),
    )

    fun load(slotIndex: Int) = viewModelScope.launch { backend.loadFilament(slotIndex) }
    fun unload(slotIndex: Int) = viewModelScope.launch { backend.unloadFilament(slotIndex) }
    fun sync() = viewModelScope.launch { backend.syncAms() }
    fun setFilamentSettings(slotIndex: Int, filamentKey: String, colorHex: String) =
        viewModelScope.launch { backend.setFilamentSettings(slotIndex, filamentKey, colorHex) }
}
