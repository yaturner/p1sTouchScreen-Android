package com.das.p1stouch.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.das.p1stouch.state.GcodeState
import com.das.p1stouch.ui.backendViewModel
import com.das.p1stouch.ui.components.PrintProgressBanner
import com.das.p1stouch.ui.navigation.Screen

/** Home screen: status bar (owned by the Scaffold TopAppBar, see P1SApp.kt)
 * + a dismissible print-progress banner while printing/paused, matching
 * home.py -- but nav is now the side drawer, not this screen's tile grid, so
 * this screen just shows a quick-glance grid of the same destinations for
 * convenience, not as the primary navigation path. */
@Composable
fun HomeScreen(onNavigate: (String) -> Unit) {
    val vm = backendViewModel(::HomeViewModel)
    val state by vm.state.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.isPrinting) {
            val verb = if (state.gcodeState == GcodeState.PAUSE) "Paused" else "Printing"
            PrintProgressBanner(
                text = "$verb: ${state.currentFile ?: "?"} — ${state.printPercent ?: 0}%",
                onClick = { onNavigate(Screen.PrintMonitor.route) },
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(Screen.drawerItems.drop(1)) { screen -> // drop Home itself
                HomeTile(screen, onClick = { onNavigate(screen.route) })
            }
        }
    }
}

@Composable
private fun HomeTile(screen: Screen, onClick: () -> Unit) {
    androidx.compose.material3.Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            screen.icon?.let {
                Icon(it, contentDescription = null, modifier = Modifier.padding(bottom = 8.dp))
            }
            Text(screen.title, style = MaterialTheme.typography.titleMedium)
        }
    }
}
