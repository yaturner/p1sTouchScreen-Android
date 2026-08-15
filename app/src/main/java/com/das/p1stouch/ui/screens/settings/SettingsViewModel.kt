package com.das.p1stouch.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.das.p1stouch.data.PrinterConfig
import com.das.p1stouch.data.PrinterConfigRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repo: PrinterConfigRepository) : ViewModel() {
    val config: StateFlow<PrinterConfig> = repo.config.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), PrinterConfig(),
    )

    fun save(config: PrinterConfig, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            repo.save(config)
            onSaved()
        }
    }
}
