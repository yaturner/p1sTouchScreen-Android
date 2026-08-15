package com.das.p1stouch.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.das.p1stouch.state.ConnectionState
import com.das.p1stouch.state.PrinterState
import com.das.p1stouch.ui.components.ConnectionOverlay
import com.das.p1stouch.ui.components.HmsBanner
import com.das.p1stouch.ui.drawer.AppDrawerContent
import com.das.p1stouch.ui.navigation.P1SNavHost
import com.das.p1stouch.ui.navigation.Screen
import com.das.p1stouch.ui.theme.P1STheme
import kotlinx.coroutines.launch

/** Top-level app shell: side-drawer nav + Scaffold (top bar, HmsBanner slot,
 * error snackbar, NavHost content, ConnectionOverlay scrim). Mirrors
 * MainWindow's role in the Python app -- owns navigation and wires backend
 * state to the pieces that aren't a specific screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun P1SApp(startDestination: String) {
    val navController = rememberNavController()
    val appViewModel = backendViewModel(::AppViewModel)
    val state by appViewModel.state.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentScreen = Screen.byRoute(currentRoute)

    // Command failures (publish errors, FTP listing failures, connection
    // drops) surface as transient toasts here rather than a persistent
    // banner -- the HmsBanner above is reserved for the printer's own
    // ongoing HMS conditions, which is a different kind of "error" (state
    // to keep showing) than "your last tap didn't go through" (an event).
    LaunchedEffect(Unit) {
        appViewModel.errors.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // Auto-navigate to Print Monitor when a print starts while on Home --
    // port of MainWindow._on_state_changed's edge-trigger.
    var wasPrinting by remember { mutableStateOf(false) }
    LaunchedEffect(state.isPrinting, currentRoute) {
        if (state.isPrinting && !wasPrinting && currentRoute == Screen.Home.route) {
            navController.navigate(Screen.PrintMonitor.route) { launchSingleTop = true }
        }
        wasPrinting = state.isPrinting
    }

    P1STheme {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                AppDrawerContent(currentRoute = currentRoute) { route ->
                    scope.launch { drawerState.close() }
                    navController.navigate(route) { launchSingleTop = true }
                }
            },
        ) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    TopAppBar(
                        title = { Text(currentScreen?.title ?: "P1S Touch") },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu")
                            }
                        },
                        actions = { StatusChip(state) },
                    )
                },
            ) { padding ->
                Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        HmsBanner(messages = state.hmsErrors, onDismiss = { /* TODO(M7): per-message dismiss */ })
                        Box(modifier = Modifier.weight(1f)) {
                            P1SNavHost(navController = navController, startDestination = startDestination)
                            // Settings/First Run must stay usable even while
                            // disconnected -- that's where connection details get fixed.
                            if (currentRoute != Screen.Settings.route && currentRoute != Screen.FirstRun.route) {
                                ConnectionOverlay(connection = state.connection)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(state: PrinterState) {
    Row(modifier = Modifier.padding(end = 12.dp)) {
        Text("N ${fmt(state.nozzleTemp)}/${fmt(state.nozzleTarget)}°  B ${fmt(state.bedTemp)}/${fmt(state.bedTarget)}°  ${dot(state.connection)}")
    }
}

private fun fmt(v: Double?): String = if (v == null) "--" else Math.round(v).toString()

private fun dot(c: ConnectionState): String = when (c) {
    ConnectionState.CONNECTED -> "🟢"
    ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> "🟡"
    ConnectionState.DISCONNECTED -> "🔴"
}
