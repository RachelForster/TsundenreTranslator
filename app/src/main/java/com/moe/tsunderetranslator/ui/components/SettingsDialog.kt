package com.moe.tsunderetranslator.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SettingsDialog(
    initialBaseUrl: String,
    initialModel: String,
    initialApiKey: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var baseUrl by rememberSaveable { mutableStateOf(initialBaseUrl) }
    var model by rememberSaveable { mutableStateOf(initialModel) }
    var apiKey by rememberSaveable { mutableStateOf(initialApiKey) }

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
            }
        },
        confirmButton = {
            Button(onClick = { onSave(baseUrl, model, apiKey) }) {
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