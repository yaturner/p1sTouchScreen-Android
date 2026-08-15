package com.das.p1stouch.ui.screens.printmonitor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.das.p1stouch.state.GcodeState
import com.das.p1stouch.ui.backendViewModel

private val SPEED_LEVELS = listOf("Silent" to 1, "Standard" to 2, "Sport" to 3, "Ludicrous" to 4)

/** Port of ui/screens/print_monitor.py: live camera feed, progress/layer/
 * ETA, pause-resume/stop, speed dropdown. Connection status and the
 * screen title come from the shared drawer scaffold's top bar (unlike the
 * Python app's own per-screen back button + state label), so this is just
 * the content column. */
@Composable
fun PrintMonitorScreen() {
    val vm = backendViewModel(::PrintMonitorViewModel)
    val state by vm.state.collectAsState()
    val frame by vm.cameraFrame.collectAsState()

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            state.gcodeState.name.lowercase().replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.titleLarge,
        )

        Box(
            modifier = Modifier.fillMaxWidth().height(240.dp).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = frame
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                )
            } else {
                Text("No camera feed", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        LinearProgressIndicator(
            progress = { (state.printPercent ?: 0) / 100f },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("Layer: ${state.currentLayer ?: "--"} / ${state.totalLayer ?: "--"}")
            Text("ETA: ${state.remainingMinutes?.let { "$it min" } ?: "--"}")
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.pauseOrResume() }) {
                Text(if (state.gcodeState == GcodeState.PAUSE) "Resume" else "Pause")
            }
            Button(onClick = { vm.stop() }) { Text("Stop") }
            SpeedDropdown(current = state.speedLevel, onSelect = { vm.setSpeedLevel(it) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedDropdown(current: Int?, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = SPEED_LEVELS.firstOrNull { it.second == current }?.first ?: "--"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("Speed") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.width(160.dp).menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SPEED_LEVELS.forEach { (label, level) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onSelect(level); expanded = false },
                )
            }
        }
    }
}
