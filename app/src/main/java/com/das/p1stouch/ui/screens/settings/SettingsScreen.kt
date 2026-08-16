package com.das.p1stouch.ui.screens.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.das.p1stouch.AppRestart
import com.das.p1stouch.BuildConfig
import com.das.p1stouch.state.ConnectionState
import com.das.p1stouch.ui.configViewModel
import com.das.p1stouch.ui.localBackend
import com.das.p1stouch.ui.theme.ThemeMode
import kotlinx.coroutines.launch

/** Port of ui/screens/settings.py, minus the desktop/kiosk-Pi-specific
 * fullscreen toggle and Restart App/Shutdown Pi buttons -- those have no
 * Android equivalent worth forcing (see the approved plan). [onSetup] is
 * the "Setup" affordance mentioned in the plan for re-reaching First Run,
 * which otherwise has no drawer entry. */
@Composable
fun SettingsScreen(onSetup: () -> Unit) {
    val vm = configViewModel(::SettingsViewModel)
    val backend = localBackend()
    val config by vm.config.collectAsState()
    val backendState by backend.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    var codeRevealed by remember { mutableStateOf(false) }
    var showSetupWarning by remember { mutableStateOf(false) }
    var showCalibrationConfirm by remember { mutableStateOf(false) }
    // Guards against a fast double-tap firing two concurrent connect()/
    // disconnect() calls -- RealBackend now serializes them internally too,
    // but disabling the button while one is in flight avoids the wasted
    // second call (and the brief lock-wait it'd otherwise cause) entirely.
    var connectionActionInFlight by remember { mutableStateOf(false) }

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

        Text("Connection: ${connectionStatusLabel(backendState.connection)}")

        val isConnected = backendState.connection == ConnectionState.CONNECTED
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                enabled = !connectionActionInFlight,
                onClick = {
                    connectionActionInFlight = true
                    scope.launch {
                        try {
                            backend.disconnect()
                            // Only immediately reconnect if that's what was
                            // asked for -- Disconnect should actually
                            // disconnect and stay that way, not bounce right
                            // back.
                            if (!isConnected) backend.connect()
                        } finally {
                            connectionActionInFlight = false
                        }
                    }
                },
            ) {
                Text(if (isConnected) "Disconnect" else "Reconnect")
            }
            OutlinedButton(onClick = {
                // Only worth warning about if there's an actual live
                // connection to lose -- saving new settings restarts the
                // app (see FirstRunScreen), which drops whatever's
                // currently connected. Nothing to warn about if already
                // disconnected.
                if (isConnected) showSetupWarning = true else onSetup()
            }) {
                Text("Printer Setup")
            }
        }

        if (showSetupWarning) {
            AlertDialog(
                onDismissRequest = { showSetupWarning = false },
                title = { Text("Change Printer Settings") },
                text = {
                    Text(
                        "Changing printer settings requires restarting the app, " +
                            "which will disconnect it from the printer. Continue?",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showSetupWarning = false }) { Text("Cancel") }
                },
                dismissButton = {
                    TextButton(onClick = { showSetupWarning = false; onSetup() }) { Text("Continue") }
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Skip thumbnails", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Don't download or show Print Files previews. This printer's FTP " +
                        "transfer is slow, so a large file library can take several " +
                        "minutes to load thumbnails for the first time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = config.skipThumbnails,
                onCheckedChange = { checked ->
                    vm.save(config.copy(skipThumbnails = checked)) { AppRestart.restart(context) }
                },
            )
        }

        Column {
            Text("Theme", style = MaterialTheme.typography.bodyLarge)
            val currentMode = ThemeMode.fromConfigValue(config.themeMode)
            Row(verticalAlignment = Alignment.CenterVertically) {
                ThemeOption("Dark", ThemeMode.DARK, currentMode) { vm.save(config.copy(themeMode = it.configValue)) }
                ThemeOption("Light", ThemeMode.LIGHT, currentMode) { vm.save(config.copy(themeMode = it.configValue)) }
                ThemeOption("Match Phone", ThemeMode.SYSTEM, currentMode) { vm.save(config.copy(themeMode = it.configValue)) }
            }
        }

        Text("App version: ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
        Text("Backend: ${config.backend}", style = MaterialTheme.typography.bodyLarge)

        // Full auto-calibration (bed leveling + vibration compensation +
        // motor noise cancellation) -- a real, multi-minute operation that
        // physically moves the extruder and print bed, so it needs the
        // same confirm-before-acting treatment as Stop Print, not a bare
        // button.
        OutlinedButton(onClick = { showCalibrationConfirm = true }) {
            Text("Run Calibration")
        }

        if (showCalibrationConfirm) {
            AlertDialog(
                onDismissRequest = { showCalibrationConfirm = false },
                title = { Text("Run Calibration") },
                text = {
                    Text(
                        "Run full calibration (bed leveling, vibration compensation, motor " +
                            "noise cancellation)? This takes several minutes and moves the " +
                            "extruder and print bed -- make sure the bed is clear.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showCalibrationConfirm = false }) { Text("Cancel") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showCalibrationConfirm = false
                        scope.launch { backend.runCalibration() }
                    }) { Text("Run") }
                },
            )
        }

        Button(onClick = { activity?.finishAffinity() }) {
            Text("Exit App")
        }
    }
}

// Matches Python's settings.py: state.connection.name.title() --
// "Connected"/"Connecting"/"Reconnecting"/"Disconnected".
private fun connectionStatusLabel(c: ConnectionState): String = when (c) {
    ConnectionState.CONNECTED -> "Connected"
    ConnectionState.CONNECTING -> "Connecting"
    ConnectionState.RECONNECTING -> "Reconnecting"
    ConnectionState.DISCONNECTED -> "Disconnected"
}

@Composable
private fun ThemeOption(label: String, mode: ThemeMode, current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = mode == current, onClick = { onSelect(mode) })
        Text(label, modifier = Modifier.padding(end = 12.dp))
    }
}
