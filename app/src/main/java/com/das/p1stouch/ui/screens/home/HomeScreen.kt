package com.das.p1stouch.ui.screens.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
                HomeTile(screen, hasHmsErrors = state.hmsErrors.isNotEmpty(), onClick = { onNavigate(screen.route) })
            }
        }
    }
}

@Composable
private fun HomeTile(screen: Screen, hasHmsErrors: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Assistant's icon reflects whether there's actually something
            // to check right now, rather than a static glyph -- a happy
            // face when nothing's wrong, an unhappy face with X eyes when
            // there's an active HMS error to look at.
            if (screen == Screen.Assistant) {
                RobotFaceIcon(sad = hasHmsErrors, modifier = Modifier.padding(bottom = 8.dp).size(40.dp))
            } else {
                // home.py's tile glyph takes priority so the two apps' home
                // screens read the same at a glance; falls back to the
                // Material icon used elsewhere (e.g. the drawer) for
                // destinations Python's home screen doesn't tile, like Print
                // Monitor.
                val emoji = screen.homeEmoji
                if (emoji != null) {
                    Text(
                        emoji,
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                } else {
                    screen.icon?.let {
                        Icon(it, contentDescription = null, modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
            }
            Text(screen.title, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Simple drawn robot face -- Unicode has no happy/sad-with-X-eyes robot
 * emoji pair, so this is hand-drawn instead of reusing the emoji-glyph
 * pattern the other tiles use. */
@Composable
private fun RobotFaceIcon(sad: Boolean, modifier: Modifier = Modifier) {
    val color = if (sad) Color(0xFFE53935) else Color(0xFF43A047)
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val headTop = h * 0.22f
        val headLeft = w * 0.08f
        val headSize = androidx.compose.ui.geometry.Size(w * 0.84f, h * 0.72f)
        val stroke = Stroke(width = w * 0.06f)

        // Antenna
        drawLine(color, Offset(w * 0.5f, headTop), Offset(w * 0.5f, 0f), strokeWidth = stroke.width)
        drawCircle(color, radius = w * 0.05f, center = Offset(w * 0.5f, h * 0.04f))

        // Head
        drawRoundRect(
            color = color,
            topLeft = Offset(headLeft, headTop),
            size = headSize,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.15f),
            style = stroke,
        )

        val eyeY = headTop + headSize.height * 0.38f
        val leftEyeX = headLeft + headSize.width * 0.3f
        val rightEyeX = headLeft + headSize.width * 0.7f
        val eyeR = w * 0.07f

        // Compose's drawArc: 0deg = 3 o'clock, angles sweep clockwise (90deg
        // = 6 o'clock/bottom, 270deg = 12 o'clock/top). A smile bulges
        // downward (the bottom arc, passing through 90deg); a frown bulges
        // upward (the top arc, passing through 270deg).
        val mouthTopLeft = Offset(headLeft + headSize.width * 0.22f, headTop + headSize.height * 0.5f)
        val mouthSize = androidx.compose.ui.geometry.Size(headSize.width * 0.56f, headSize.height * 0.34f)
        if (sad) {
            // X eyes
            listOf(leftEyeX, rightEyeX).forEach { cx ->
                drawLine(color, Offset(cx - eyeR, eyeY - eyeR), Offset(cx + eyeR, eyeY + eyeR), strokeWidth = stroke.width * 0.7f)
                drawLine(color, Offset(cx - eyeR, eyeY + eyeR), Offset(cx + eyeR, eyeY - eyeR), strokeWidth = stroke.width * 0.7f)
            }
            // Frown mouth (top arc, bulges upward)
            drawArc(
                color = color,
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = mouthTopLeft,
                size = mouthSize,
                style = Stroke(width = stroke.width * 0.7f),
            )
        } else {
            // Dot eyes
            drawCircle(color, radius = eyeR * 0.7f, center = Offset(leftEyeX, eyeY))
            drawCircle(color, radius = eyeR * 0.7f, center = Offset(rightEyeX, eyeY))
            // Smile mouth (bottom arc, bulges downward)
            drawArc(
                color = color,
                startAngle = 20f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = mouthTopLeft,
                size = mouthSize,
                style = Stroke(width = stroke.width * 0.7f),
            )
        }
    }
}
