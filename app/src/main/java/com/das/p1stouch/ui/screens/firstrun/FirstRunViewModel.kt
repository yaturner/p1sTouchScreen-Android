package com.das.p1stouch.ui.screens.firstrun

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.das.p1stouch.data.PrinterConfig
import com.das.p1stouch.data.PrinterConfigRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FirstRunViewModel(private val repo: PrinterConfigRepository) : ViewModel() {
    val config: StateFlow<PrinterConfig> = repo.config.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), PrinterConfig(),
    )

    fun save(ip: String, serial: String, accessCode: String, onSaved: () -> Unit) {
        viewModelScope.launch {
            repo.save(PrinterConfig(ip = ip.trim(), serial = serial.trim(), accessCode = accessCode.trim(), backend = "real"))
            onSaved()
        }
    }
}
