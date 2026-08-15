package com.das.p1stouch.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.das.p1stouch.ui.theme.AccentGreen

/** Dismissible-by-navigation banner shown on Home while printing/paused;
 * tapping it opens Print Monitor. Port of home.py's print-progress banner. */
@Composable
fun PrintProgressBanner(text: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, AccentGreen),
    ) {
        Text(text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
    }
}
