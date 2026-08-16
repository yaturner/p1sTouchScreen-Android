package com.das.p1stouch.ui.screens.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.das.p1stouch.ui.backendViewModel

/** Port of ui/screens/assistant.py: the full list of the printer's
 * currently-active HMS errors, each in its own card. Reached via the
 * HmsBanner's "Check Solution" button or the Home tile -- the banner
 * itself only shows a joined single block, so this is the roomier,
 * one-per-card view of the exact same underlying list. Also hosts a Run
 * Calibration button -- a real, multi-minute operation that physically
 * moves the extruder and print bed, so it needs the same confirm-before-
 * acting treatment as Stop Print, not a bare button. */
@Composable
fun AssistantScreen() {
    val vm = backendViewModel(::AssistantViewModel)
    val state by vm.state.collectAsState()
    var showCalibrationConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row {
            OutlinedButton(onClick = { showCalibrationConfirm = true }) {
                Text("Run Calibration")
            }
        }

        if (state.hmsErrors.isEmpty()) {
            Text("No active HMS errors.", style = MaterialTheme.typography.bodyLarge)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.hmsErrors) { message ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(message, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
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
                TextButton(onClick = { showCalibrationConfirm = false; vm.runCalibration() }) { Text("Run") }
            },
        )
    }
}
