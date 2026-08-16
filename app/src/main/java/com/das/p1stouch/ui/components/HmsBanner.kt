package com.das.p1stouch.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.das.p1stouch.ui.theme.WarningAmber
import com.das.p1stouch.ui.theme.WarningBackground

/** Dismissible banner for active HMS errors. Port of ui/widgets/hms_banner.py
 * -- unlike the Python version's separate "transient toast" mode, this one
 * only renders the persistent error list; command-failure toasts are handled
 * via a Snackbar at the Scaffold level instead (more idiomatic on Android). */
@Composable
fun HmsBanner(
    messages: List<String>,
    onDismiss: () -> Unit,
    onCheckSolution: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = messages.isNotEmpty()) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(WarningBackground)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                messages.joinToString("\n"),
                color = WarningAmber,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = onCheckSolution,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = WarningAmber),
                border = BorderStroke(1.dp, WarningAmber),
            ) {
                Text("Check Solution")
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = WarningAmber)
            }
        }
    }
}
