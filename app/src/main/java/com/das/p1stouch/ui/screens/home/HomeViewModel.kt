package com.das.p1stouch.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.das.p1stouch.printer.PrinterBackend
import com.das.p1stouch.state.PrinterState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class HomeViewModel(backend: PrinterBackend) : ViewModel() {
    val state: StateFlow<PrinterState> = backend.state.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), PrinterState(),
    )
}
