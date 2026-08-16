package com.das.p1stouch.ui.screens.filamentams

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.das.p1stouch.printer.FilamentPresets
import com.das.p1stouch.state.AMSTray

// A practical palette rather than a full color wheel -- Compose has no
// built-in system color picker, and a touchscreen kiosk doesn't need one;
// tapping a swatch fills the hex field below, which is the actual value
// sent.
private val PALETTE = listOf(
    "#FFFFFF", "#000000", "#EC008C", "#5E43B7", "#1E88E5", "#43A047",
    "#F4EE2A", "#FB8C00", "#E53935", "#8D6E63", "#9E9E9E", "#00ACC1",
)

/** Port of the Python app's AmsEditDialog: edits a slot's stored filament
 * type + color via ams_filament_setting. Deliberately has no "Dynamic
 * pressure control" section -- no local MQTT command sets a manual K/N
 * pressure-advance override (checked bambulabs_api's full command set),
 * only the full auto-calibration sequence, so it's left out rather than
 * guessed at. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmsEditDialog(slotIndex: Int, tray: AMSTray, onDismiss: () -> Unit, onConfirm: (filamentKey: String, colorHex: String) -> Unit) {
    var manufacturer by remember { mutableStateOf(FilamentPresets.MANUFACTURERS.first()) }
    val presets = FilamentPresets.PRESETS_BY_MANUFACTURER[manufacturer].orEmpty()
    // Best-effort: land on a preset whose material matches the tray's
    // reported type (e.g. "PLA"), not necessarily its exact sub-variant
    // (Matte/Silk/etc, which telemetry doesn't report). The printer never
    // reports which brand a spool is, so manufacturer always starts on
    // Bambu Lab regardless.
    var typeLabel by remember {
        mutableStateOf(presets.firstOrNull { it.trayType == tray.filamentType }?.label ?: presets.firstOrNull()?.label ?: "")
    }
    var colorHex by remember { mutableStateOf((tray.colorHex ?: "#FFFFFF").uppercase()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Slot ${slotIndex + 1}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledDropdown(
                    label = "Manufacturer",
                    options = FilamentPresets.MANUFACTURERS,
                    selected = manufacturer,
                    onSelect = { selected ->
                        manufacturer = selected
                        val newPresets = FilamentPresets.PRESETS_BY_MANUFACTURER[selected].orEmpty()
                        typeLabel = newPresets.firstOrNull { it.label == typeLabel }?.label
                            ?: newPresets.firstOrNull()?.label ?: ""
                    },
                )
                LabeledDropdown(
                    label = "Filament",
                    options = presets.map { it.label },
                    selected = typeLabel,
                    onSelect = { typeLabel = it },
                )
                Text("Color", style = MaterialTheme.typography.labelLarge)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    modifier = Modifier.height(84.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(PALETTE) { hex ->
                        val selected = hex.equals(colorHex, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(parseHexColor(hex))
                                .border(
                                    width = if (selected) 2.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = CircleShape,
                                )
                                .clickable { colorHex = hex },
                        )
                    }
                }
                OutlinedTextField(
                    value = colorHex,
                    onValueChange = { colorHex = it.uppercase() },
                    label = { Text("Hex color") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (typeLabel.isNotEmpty()) onConfirm(FilamentPresets.presetKey(manufacturer, typeLabel), colorHex)
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabeledDropdown(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

private fun parseHexColor(hex: String): Color {
    val stripped = hex.removePrefix("#")
    return try {
        Color(
            stripped.substring(0, 2).toInt(16),
            stripped.substring(2, 4).toInt(16),
            stripped.substring(4, 6).toInt(16),
        )
    } catch (e: Exception) {
        Color(0xFF3A3A3A)
    }
}
