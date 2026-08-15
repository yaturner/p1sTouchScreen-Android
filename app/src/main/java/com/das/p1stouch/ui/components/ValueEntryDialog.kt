package com.das.p1stouch.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType

/**
 * One reusable numeric-entry dialog, standing in for the Python app's
 * custom on-screen NumericKeypadDialog. That widget existed because a bare
 * Pi framebuffer had no guaranteed on-screen keyboard; Android always has a
 * system IME, so a plain text field is the more idiomatic replacement (see
 * the approved plan). Every numeric entry point in Control routes through
 * this one composable, matching the Python app's single reusable dialog.
 */
@Composable
fun ValueEntryDialog(
    title: String,
    unitLabel: String,
    initialValue: Double,
    min: Double,
    max: Double,
    allowDecimal: Boolean = true,
    allowNegative: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    var text by remember {
        mutableStateOf(if (initialValue == Math.floor(initialValue)) initialValue.toInt().toString() else initialValue.toString())
    }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = { Text(unitLabel) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (allowDecimal) KeyboardType.Decimal else KeyboardType.Number,
                    ),
                    singleLine = true,
                    isError = error != null,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val value = text.toDoubleOrNull()
                error = when {
                    value == null -> "Enter a number"
                    !allowNegative && value < 0 -> "Must be positive"
                    value < min || value > max -> "Must be between ${fmt(min)} and ${fmt(max)}"
                    else -> null
                }
                if (error == null && value != null) onConfirm(value)
            }) { Text("Confirm") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun fmt(v: Double): String = if (v == Math.floor(v)) v.toInt().toString() else v.toString()
