package com.moe.tsunderetranslator.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SettingsDialog(
    initialBaseUrl: String,
    initialModel: String,
    initialApiKey: String,
    initialTtsBaseUrl: String,
    initialTtsCharacterName: String,
    initialTtsRefAudioPath: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, String) -> Unit
) {
    var baseUrl by rememberSaveable { mutableStateOf(initialBaseUrl) }
    var model by rememberSaveable { mutableStateOf(initialModel) }
    var apiKey by rememberSaveable { mutableStateOf(initialApiKey) }
    var ttsBaseUrl by rememberSaveable { mutableStateOf(initialTtsBaseUrl) }
    var ttsCharacterName by rememberSaveable { mutableStateOf(initialTtsCharacterName) }
    var ttsRefAudioPath by rememberSaveable { mutableStateOf(initialTtsRefAudioPath) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("LLM Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(
                    value = ttsBaseUrl,
                    onValueChange = { ttsBaseUrl = it },
                    label = { Text("TTS Base URL") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = ttsCharacterName,
                    onValueChange = { ttsCharacterName = it },
                    label = { Text("TTS Character Name") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = ttsRefAudioPath,
                    onValueChange = { ttsRefAudioPath = it },
                    label = { Text("TTS Ref Audio Path") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(baseUrl, model, apiKey, ttsBaseUrl, ttsCharacterName, ttsRefAudioPath)
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
