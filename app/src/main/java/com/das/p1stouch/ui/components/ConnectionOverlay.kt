package com.das.p1stouch.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.das.p1stouch.state.ConnectionState

private val MESSAGES = mapOf(
    ConnectionState.DISCONNECTED to "Not connected",
    ConnectionState.CONNECTING to "Connecting to printer…",
    ConnectionState.RECONNECTING to "Connection lost — retrying…",
)

/** Full-content scrim shown while disconnected/reconnecting. Port of
 * ui/widgets/connection_overlay.py. Caller is responsible for not showing
 * this on Settings/First Run, same allowlist as the Python app's
 * _update_overlay_visibility. */
@Composable
fun ConnectionOverlay(connection: ConnectionState, modifier: Modifier = Modifier) {
    val message = MESSAGES[connection]
    AnimatedVisibility(visible = message != null) {
        Box(
            modifier = modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(message ?: "", color = Color.White, style = MaterialTheme.typography.titleLarge)
        }
    }
}
