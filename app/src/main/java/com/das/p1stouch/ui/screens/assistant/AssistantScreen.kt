package com.das.p1stouch.ui.screens.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.das.p1stouch.ui.backendViewModel

/** Port of ui/screens/assistant.py: the full list of the printer's
 * currently-active HMS errors, each in its own card. Reached via the
 * HmsBanner's "Check Solution" button or the Home tile -- the banner
 * itself only shows a joined single block, so this is the roomier,
 * one-per-card view of the exact same underlying list. */
@Composable
fun AssistantScreen() {
    val vm = backendViewModel(::AssistantViewModel)
    val state by vm.state.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
}
