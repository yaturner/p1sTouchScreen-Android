package com.das.p1stouch.ui.screens.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.das.p1stouch.BuildConfig
import com.das.p1stouch.ui.configViewModel
import com.das.p1stouch.ui.localBackend
import kotlinx.coroutines.launch

/** Port of ui/screens/settings.py, minus the desktop/kiosk-Pi-specific
 * fullscreen toggle and Restart App/Shutdown Pi buttons -- those have no
 * Android equivalent worth forcing (see the approved plan). */
@Composable
fun SettingsScreen() {
    val vm = configViewModel(::SettingsViewModel)
    val backend = localBackend()
    val config by vm.config.collectAsState()
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as? ComponentActivity

    var codeRevealed by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Printer IP: ${config.ip.ifBlank { "(not set)" }}")
        Text("Serial: ${config.serial.ifBlank { "(not set)" }}")

        val maskedCode = if (config.accessCode.isBlank()) {
            "Access code: (not set)"
        } else {
            val shown = if (codeRevealed) config.accessCode else "•".repeat(config.accessCode.length)
            "Access code: $shown  (tap to ${if (codeRevealed) "hide" else "reveal"})"
        }
        Text(maskedCode, modifier = Modifier.clickable { codeRevealed = !codeRevealed })

        OutlinedButton(onClick = {
            scope.launch {
                backend.disconnect()
                backend.connect()
            }
        }) {
            Text("Reconnect")
        }

        Text("App version: ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
        Text("Backend: ${config.backend}", style = MaterialTheme.typography.bodyLarge)

        Button(onClick = { activity?.finishAffinity() }) {
            Text("Exit App")
        }
    }
}
