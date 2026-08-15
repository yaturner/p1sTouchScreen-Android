package com.das.p1stouch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.das.p1stouch.ui.P1SApp
import com.das.p1stouch.ui.navigation.Screen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // A single local-disk DataStore read at startup, matching the Python
        // app's synchronous config.yaml load -- avoids a startDestination flash.
        val configRepository = (application as App).configRepository
        val isReady = runBlocking { configRepository.config.first().isReady }
        val startDestination = if (isReady) Screen.Home.route else Screen.FirstRun.route

        setContent {
            P1SApp(startDestination = startDestination)
        }
    }
}
