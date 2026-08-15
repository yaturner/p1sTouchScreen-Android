package com.das.p1stouch.ui.screens.firstrun

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.das.p1stouch.ui.configViewModel

/** Port of ui/screens/first_run.py: one-time printer connection setup. Not
 * in the drawer -- reached only as the startDestination when config isn't
 * ready yet, or later via a "Setup" affordance in Settings. */
@Composable
fun FirstRunScreen(onDone: (skippedToHome: Boolean) -> Unit) {
    val vm = configViewModel(::FirstRunViewModel)
    val config by vm.config.collectAsState()

    var ip by remember { mutableStateOf("") }
    var serial by remember { mutableStateOf("") }
    var accessCode by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var prefilled by remember { mutableStateOf(false) }

    LaunchedEffect(config) {
        if (!prefilled) {
            ip = config.ip
            serial = config.serial
            accessCode = config.accessCode
            prefilled = true
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Printer Setup", style = MaterialTheme.typography.titleLarge)
        Text(
            "Find these in Bambu Studio or Handy: select your P1S -> Settings -> " +
                "enable \"LAN Only Mode\".",
            style = MaterialTheme.typography.bodyLarge,
        )

        OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text("IP address") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = serial, onValueChange = { serial = it }, label = { Text("Serial number") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = accessCode,
            onValueChange = { accessCode = it },
            label = { Text("Access code") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Button(onClick = {
            if (ip.isBlank() || serial.isBlank() || accessCode.isBlank()) {
                error = "All fields are required."
            } else {
                error = null
                vm.save(ip, serial, accessCode) { onDone(false) }
            }
        }) {
            Text("Save & Continue")
        }

        OutlinedButton(onClick = { onDone(true) }) {
            Text("Skip (use demo mode)")
        }
    }
}
