package com.das.p1stouch.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
            // A plain Box has no gesture handler by default, so touches
            // fall through to whatever's underneath in z-order despite
            // this being visually on top -- detectTapGestures with no-op
            // callbacks consumes every tap here instead, actually blocking
            // interaction with the screen behind the scrim.
            modifier = modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.78f))
                .pointerInput(Unit) { detectTapGestures { } },
            contentAlignment = Alignment.Center,
        ) {
            Text(message ?: "", color = Color.White, style = MaterialTheme.typography.titleLarge)
        }
    }
}
