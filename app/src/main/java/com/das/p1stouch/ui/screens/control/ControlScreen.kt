package com.das.p1stouch.ui.screens.control

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.das.p1stouch.ui.backendViewModel
import com.das.p1stouch.ui.components.ValueEntryDialog

private val STEP_SIZES = listOf(0.1, 1.0, 10.0)

/** Port of ui/screens/control.py: jog pad + home, step size, extrude/
 * retract (via [ValueEntryDialog] instead of the Python app's custom
 * keypad), nozzle/bed target temps, fan speeds, light toggle. */
@Composable
fun ControlScreen() {
    val vm = backendViewModel(::ControlViewModel)
    val state by vm.state.collectAsState()

    var stepMm by remember { mutableDoubleStateOf(1.0) }
    var activeDialog by remember { mutableStateOf<ControlDialog?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Jog", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            JogPad(
                onJog = { axis, sign -> vm.jog(axis, sign * stepMm) },
                onHome = { vm.home() },
            )
            ZJogColumn(onJog = { sign -> vm.jog('Z', sign * stepMm) })
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Step:", modifier = Modifier.padding(end = 8.dp))
            STEP_SIZES.forEach { size ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = stepMm == size, onClick = { stepMm = size })
                    Text("${fmtStep(size)}mm", modifier = Modifier.padding(end = 12.dp))
                }
            }
        }

        HorizontalDivider()

        Text("Extrude", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { activeDialog = ControlDialog.Extrude }) { Text("Extrude") }
            OutlinedButton(onClick = { activeDialog = ControlDialog.Retract }) { Text("Retract") }
        }

        HorizontalDivider()

        Text("Temperature", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { activeDialog = ControlDialog.NozzleTemp }) {
                Text("Nozzle: ${fmtTemp(state.nozzleTemp)}/${fmtTemp(state.nozzleTarget)}°")
            }
            OutlinedButton(onClick = { activeDialog = ControlDialog.BedTemp }) {
                Text("Bed: ${fmtTemp(state.bedTemp)}/${fmtTemp(state.bedTarget)}°")
            }
        }

        HorizontalDivider()

        Text("Fans", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { activeDialog = ControlDialog.PartFan }) {
                Text("Part: ${state.fanSpeeds["part"] ?: 0}%")
            }
            OutlinedButton(onClick = { activeDialog = ControlDialog.AuxFan }) {
                Text("Aux: ${state.fanSpeeds["aux"] ?: 0}%")
            }
            OutlinedButton(onClick = { activeDialog = ControlDialog.ChamberFan }) {
                Text("Chamber: ${state.fanSpeeds["chamber"] ?: 0}%")
            }
        }

        HorizontalDivider()

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Light", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 12.dp))
            Switch(checked = state.lightOn == true, onCheckedChange = { vm.setLight(it) })
        }
    }

    when (activeDialog) {
        ControlDialog.Extrude -> ValueEntryDialog(
            title = "Extrude", unitLabel = "mm", initialValue = 10.0, min = 0.0, max = 200.0,
            onDismiss = { activeDialog = null },
            onConfirm = { vm.extrude(it); activeDialog = null },
        )
        ControlDialog.Retract -> ValueEntryDialog(
            title = "Retract", unitLabel = "mm", initialValue = 10.0, min = 0.0, max = 200.0,
            onDismiss = { activeDialog = null },
            onConfirm = { vm.extrude(-it); activeDialog = null },
        )
        ControlDialog.NozzleTemp -> ValueEntryDialog(
            title = "Nozzle Target", unitLabel = "°C", initialValue = state.nozzleTarget ?: 0.0, min = 0.0, max = 300.0,
            allowDecimal = false,
            onDismiss = { activeDialog = null },
            onConfirm = { vm.setNozzleTarget(it.toInt()); activeDialog = null },
        )
        ControlDialog.BedTemp -> ValueEntryDialog(
            title = "Bed Target", unitLabel = "°C", initialValue = state.bedTarget ?: 0.0, min = 0.0, max = 120.0,
            allowDecimal = false,
            onDismiss = { activeDialog = null },
            onConfirm = { vm.setBedTarget(it.toInt()); activeDialog = null },
        )
        ControlDialog.PartFan -> FanDialog("Part Fan", state.fanSpeeds["part"] ?: 0, { activeDialog = null }) { vm.setFanSpeed("part", it) }
        ControlDialog.AuxFan -> FanDialog("Aux Fan", state.fanSpeeds["aux"] ?: 0, { activeDialog = null }) { vm.setFanSpeed("aux", it) }
        ControlDialog.ChamberFan -> FanDialog("Chamber Fan", state.fanSpeeds["chamber"] ?: 0, { activeDialog = null }) { vm.setFanSpeed("chamber", it) }
        null -> {}
    }
}

@Composable
private fun FanDialog(title: String, currentPercent: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    ValueEntryDialog(
        title = title, unitLabel = "%", initialValue = currentPercent.toDouble(), min = 0.0, max = 100.0,
        allowDecimal = false,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(it.toInt()); onDismiss() },
    )
}

private enum class ControlDialog { Extrude, Retract, NozzleTemp, BedTemp, PartFan, AuxFan, ChamberFan }

@Composable
private fun JogPad(onJog: (Char, Double) -> Unit, onHome: () -> Unit) {
    val btnSize = 64.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        JogButton("Y+", btnSize) { onJog('Y', 1.0) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            JogButton("X-", btnSize) { onJog('X', -1.0) }
            JogButton("⌂", btnSize, onClick = onHome)
            JogButton("X+", btnSize) { onJog('X', 1.0) }
        }
        JogButton("Y-", btnSize) { onJog('Y', -1.0) }
    }
}

@Composable
private fun ZJogColumn(onJog: (Double) -> Unit) {
    val btnSize = 64.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Z")
        JogButton("Z+", btnSize) { onJog(1.0) }
        JogButton("Z-", btnSize) { onJog(-1.0) }
    }
}

@Composable
private fun JogButton(label: String, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.size(size)) { Text(label) }
}

private fun fmtTemp(v: Double?): String = if (v == null) "--" else Math.round(v).toString()
private fun fmtStep(v: Double): String = if (v == Math.floor(v)) v.toInt().toString() else v.toString()
