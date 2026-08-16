package com.das.p1stouch.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.das.p1stouch.ui.screens.assistant.AssistantScreen
import com.das.p1stouch.ui.screens.control.ControlScreen
import com.das.p1stouch.ui.screens.filamentams.FilamentAmsScreen
import com.das.p1stouch.ui.screens.firstrun.FirstRunScreen
import com.das.p1stouch.ui.screens.home.HomeScreen
import com.das.p1stouch.ui.screens.printfiles.PrintFilesScreen
import com.das.p1stouch.ui.screens.printmonitor.PrintMonitorScreen
import com.das.p1stouch.ui.screens.settings.SettingsScreen

@Composable
fun P1SNavHost(navController: NavHostController, startDestination: String) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Screen.Home.route) {
            HomeScreen(onNavigate = { route -> navController.navigate(route) { launchSingleTop = true } })
        }
        composable(Screen.PrintFiles.route) { PrintFilesScreen() }
        composable(Screen.PrintMonitor.route) { PrintMonitorScreen() }
        composable(Screen.FilamentAms.route) { FilamentAmsScreen() }
        composable(Screen.Control.route) { ControlScreen() }
        composable(Screen.Settings.route) {
            SettingsScreen(onSetup = { navController.navigate(Screen.FirstRun.route) })
        }
        composable(Screen.Assistant.route) { AssistantScreen() }
        composable(Screen.FirstRun.route) {
            FirstRunScreen(onSkip = {
                navController.navigate(Screen.Home.route) {
                    popUpTo(Screen.FirstRun.route) { inclusive = true }
                }
            })
        }
    }
}
