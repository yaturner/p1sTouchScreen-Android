package com.das.p1stouch.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.das.p1stouch.printer.PrinterBackend
import com.das.p1stouch.state.PrinterState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Activity-scoped: backs the top-level Scaffold (status chip, HmsBanner,
 * error snackbar, ConnectionOverlay) and the auto-navigate-to-Print-Monitor
 * side effect, mirroring MainWindow's role in the Python app. */
class AppViewModel(private val backend: PrinterBackend) : ViewModel() {
    val state: StateFlow<PrinterState> = backend.state.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), PrinterState(),
    )

    val errors: SharedFlow<String> = backend.errors
    val printStartWarnings: SharedFlow<String> = backend.printStartWarnings

    // Dismissing the banner hides whatever's currently showing, but must
    // not hide a genuine NEW occurrence of the same fault forever -- this
    // is a 3D printer, and HMS entries can be safety-relevant (thermal
    // runaway etc.). So the dismissed set is cleared the moment the
    // printer's own HMS list goes empty, meaning "dismissed" only lasts
    // while the underlying condition is continuously active.
    private val dismissedHmsErrors = MutableStateFlow<Set<String>>(emptySet())

    val visibleHmsErrors: StateFlow<List<String>> = combine(state, dismissedHmsErrors) { s, dismissed ->
        s.hmsErrors.filterNot { it in dismissed }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            state.collect { s ->
                if (s.hmsErrors.isEmpty() && dismissedHmsErrors.value.isNotEmpty()) {
                    dismissedHmsErrors.value = emptySet()
                }
            }
        }
    }

    fun dismissHmsErrors() {
        dismissedHmsErrors.value = state.value.hmsErrors.toSet()
    }

    fun confirmPendingPrint() {
        viewModelScope.launch { backend.confirmPendingPrint() }
    }

    fun cancelPendingPrint() {
        viewModelScope.launch { backend.cancelPendingPrint() }
    }
}
