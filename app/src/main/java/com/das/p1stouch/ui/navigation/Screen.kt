package com.das.p1stouch.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/** Route + drawer metadata for every navigable destination. FirstRun is
 * deliberately not [drawerItems] -- it's an entry gate only, reached via
 * startDestination logic or a "Setup" affordance in Settings, matching the
 * Python app's screen registry. */
sealed class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector? = null,
    // Same glyph home.py's tile grid uses for this destination -- HomeScreen
    // prefers this over [icon] so the two apps' home screens read the same
    // at a glance. Null where the Python app has no tile for this route
    // (Print Monitor -- reached there via the print-progress banner, not a
    // tile) or no icon at all (Home, First Run).
    val homeEmoji: String? = null,
) {
    data object Home : Screen("home", "Home", Icons.Filled.Home)
    data object PrintFiles : Screen("print_files", "Print Files", Icons.AutoMirrored.Filled.List, "🗂")
    data object PrintMonitor : Screen("print_monitor", "Print Monitor", Icons.Filled.PlayArrow)
    data object FilamentAms : Screen("filament_ams", "Filament", Icons.Filled.Refresh, "🧵")
    data object Control : Screen("control", "Control", Icons.Filled.Build, "🎛")
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings, "⚙")
    data object FirstRun : Screen("first_run", "Setup")

    companion object {
        // Lazy, not eagerly built: a companion object referencing its own
        // sealed class's nested `data object` siblings at class-init time is
        // a known JVM static-initialization-order hazard (the sibling
        // objects' INSTANCE fields aren't guaranteed set yet when the
        // companion's own <clinit> runs) -- observed as a real NPE on device
        // ("getRoute() on a null object reference") until this was made lazy.
        val drawerItems: List<Screen> by lazy {
            listOf(Home, PrintFiles, PrintMonitor, FilamentAms, Control, Settings)
        }

        fun byRoute(route: String?): Screen? = when (route) {
            Home.route -> Home
            PrintFiles.route -> PrintFiles
            PrintMonitor.route -> PrintMonitor
            FilamentAms.route -> FilamentAms
            Control.route -> Control
            Settings.route -> Settings
            FirstRun.route -> FirstRun
            else -> null
        }
    }
}
