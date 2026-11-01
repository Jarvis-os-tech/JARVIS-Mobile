package com.jarvis.os.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.jarvis.os.sync.ApiCredentials

@Composable
fun ApiKeySetupDialog(onDismiss: () -> Unit, onScanPc: () -> Unit,
    onSave: (String, Map<String, String>) -> String?) {
    var manual by remember { mutableStateOf(false) }
    var gemini by remember { mutableStateOf("") }
    var optional by remember { mutableStateOf(emptyMap<String, String>()) }
    var showOptional by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Set up JARVIS") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (!manual) {
                Text("Choose how to connect your assistant.")
                Button(onClick = onScanPc, modifier = Modifier.fillMaxWidth()) { Text("I have JARVIS on my PC") }
                Text("Tell PC JARVIS: “Open the phone pairing QR code.” Scan it to use your PC’s configured Gemini key. PC integrations keep using their keys on the PC.")
                OutlinedButton(onClick = { manual = true }, modifier = Modifier.fillMaxWidth()) { Text("Use my own API key") }
                Text("No PC needed. Add your Gemini key to use voice and phone controls.")
            } else {
                Text("Gemini is the primary key and is required for voice. Your provider’s usage charges apply.")
                OutlinedTextField(value = gemini, onValueChange = { gemini = it; error = null },
                    label = { Text("Gemini API key · Required") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { showOptional = !showOptional }) { Text("Other API keys · Optional") }
                if (showOptional) {
                    Text("These are saved for optional integrations. They do not replace Gemini or enable additional voice providers in this version.")
                    ApiCredentials.optionalProviders.forEach { (provider, title) ->
                        OutlinedTextField(value = optional[provider].orEmpty(),
                            onValueChange = { optional = optional + (provider to it) },
                            label = { Text("$title · Optional") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    }
                }
                Text("Keys are encrypted on this phone. They are never included in public builds or sent to the update server. Entering your own key switches to standalone phone mode.")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        if (manual) TextButton(onClick = { error = onSave(gemini, optional) }) { Text("Save keys") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}
