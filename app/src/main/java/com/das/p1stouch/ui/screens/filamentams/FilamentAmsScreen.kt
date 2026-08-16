package com.das.p1stouch.ui.screens.filamentams

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.das.p1stouch.state.AMSTray
import com.das.p1stouch.ui.backendViewModel
import kotlin.math.roundToInt

/** Port of ui/screens/filament_ams.py + ui/widgets/ams_slot.py: an AMS
 * grid, each slot showing a color swatch, filament type (or "Empty"), an
 * active-tray indicator, and Load/Unload buttons. The Python app's single
 * row of 4 was sized for a 1024px-wide Pi touchscreen; on a phone that
 * left Load/Unload button text wrapping onto three lines (confirmed
 * live), so this adapts to orientation instead -- one slot per row in
 * portrait (narrow), two per row in landscape (wide enough for the
 * Python layout's proportions). */
@Composable
fun FilamentAmsScreen() {
    val vm = backendViewModel(::FilamentAmsViewModel)
    val state by vm.state.collectAsState()
    val byIndex = state.amsTrays.associateBy { it.slotIndex }
    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val columns = if (isPortrait) 1 else 2
    var editingSlot by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val temp = state.amsTemp?.let { "${it.roundToInt()}°C" } ?: "--°C"
            val humidity = state.amsHumidityPercent?.let { "$it% RH" } ?: "--% RH"
            Text("AMS: $temp · $humidity", style = MaterialTheme.typography.bodyMedium)
            // Re-requests the printer's current AMS state immediately --
            // no local command forces the AMS hardware itself to re-scan a
            // spool's RFID tag, this just skips the wait for the next
            // periodic refresh (e.g. right after swapping a spool).
            // Disabled while busy for the same reason as Load/Unload below.
            OutlinedButton(onClick = { vm.sync() }, enabled = !state.amsBusy) { Text("Sync") }
        }
        for (rowStart in 0 until 4 step columns) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (i in rowStart until rowStart + columns) {
                    AmsSlotCard(
                        tray = byIndex[i] ?: AMSTray(slotIndex = i),
                        busy = state.amsBusy,
                        onLoad = { vm.load(i) },
                        onUnload = { vm.unload(i) },
                        onEdit = { editingSlot = i },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    editingSlot?.let { slot ->
        AmsEditDialog(
            slotIndex = slot,
            tray = byIndex[slot] ?: AMSTray(slotIndex = slot),
            onDismiss = { editingSlot = null },
            onConfirm = { filamentKey, colorHex ->
                vm.setFilamentSettings(slot, filamentKey, colorHex)
                editingSlot = null
            },
        )
    }
}

@Composable
private fun AmsSlotCard(
    tray: AMSTray,
    busy: Boolean,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.border(
            width = if (tray.isActive) 2.dp else 0.dp,
            color = if (tray.isActive) MaterialTheme.colorScheme.primary else Color.Transparent,
            shape = RoundedCornerShape(8.dp),
        ),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(parseAmsColor(tray.colorHex)),
            ) {}

            val label = if (tray.isEmpty) {
                "Slot ${tray.slotIndex + 1}: Empty"
            } else {
                // Prefer the RFID's specific product name (e.g. "PLA
                // Translucent") over the generic material category ("PLA")
                // when the printer reports one.
                val name = tray.subBrand ?: tray.filamentType ?: "?"
                "Slot ${tray.slotIndex + 1}: $name${if (tray.isActive) " • active" else ""}"
            }
            Text(label, style = MaterialTheme.typography.bodyMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Empty slot: nothing to feed in or retract, so both are
                // meaningless regardless of busy state.
                Button(
                    onClick = onLoad,
                    enabled = !busy && !tray.isEmpty,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                ) { Text("Load", style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                FilledTonalButton(
                    onClick = onUnload,
                    enabled = !busy && !tray.isEmpty,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                ) { Text("Unload", style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                OutlinedButton(
                    onClick = onEdit,
                    // Unlike Load/Unload, Edit is meaningful on an empty
                    // slot too -- it pre-labels a slot's filament type/color
                    // before a spool is physically inserted, same as the
                    // printer's own Edit-slot screen allows.
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                ) { Text("Edit", style = MaterialTheme.typography.labelMedium, maxLines = 1) }
            }
        }
    }
}

// AMSTray.colorHex is normalized to "#RRGGBB" at the source (see
// PrinterTelemetry.normalizeColor / MockBackend's own literals) -- alpha
// from the printer's raw RRGGBBAA is already dropped there, matching the
// Python app's simple color-or-fallback logic.
private fun parseAmsColor(hex: String?): Color {
    val stripped = hex?.removePrefix("#") ?: return Color(0xFF3A3A3A)
    if (stripped.length < 6) return Color(0xFF3A3A3A)
    return try {
        val r = stripped.substring(0, 2).toInt(16)
        val g = stripped.substring(2, 4).toInt(16)
        val b = stripped.substring(4, 6).toInt(16)
        Color(r, g, b)
    } catch (e: NumberFormatException) {
        Color(0xFF3A3A3A)
    }
}
