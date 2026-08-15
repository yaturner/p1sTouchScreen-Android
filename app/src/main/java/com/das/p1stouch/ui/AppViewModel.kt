package com.das.p1stouch.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.das.p1stouch.printer.PrinterBackend
import com.das.p1stouch.state.PrinterState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Activity-scoped: backs the top-level Scaffold (status chip, HmsBanner,
 * error snackbar, ConnectionOverlay) and the auto-navigate-to-Print-Monitor
 * side effect, mirroring MainWindow's role in the Python app. */
class AppViewModel(backend: PrinterBackend) : ViewModel() {
    val state: StateFlow<PrinterState> = backend.state.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), PrinterState(),
    )

    val errors: SharedFlow<String> = backend.errors
}
