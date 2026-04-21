package com.moe.tsunderetranslator.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moe.tsunderetranslator.ui.screens.chat.TtsDebugState

@Composable
fun TtsDebugDialog(
    state: TtsDebugState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onCopyAll: (String) -> Unit
) {
    val combinedLog = buildString {
        appendLine("[Local Logs]")
        if (state.localLogs.isEmpty()) {
            appendLine("(empty)")
        } else {
            state.localLogs.forEach { appendLine(it) }
        }
        appendLine()
        appendLine("[Server Logs]")
        if (state.serverLogs.isEmpty()) {
            appendLine("(empty)")
        } else {
            state.serverLogs.forEach { appendLine(it) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("TTS Debug Logs") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onRefresh,
                        modifier = Modifier.weight(1f),
                        enabled = !state.isRefreshing && !state.isClearing
                    ) {
                        Text(if (state.isRefreshing) "Refreshing..." else "Refresh")
                    }
                    OutlinedButton(
                        onClick = onClear,
                        modifier = Modifier.weight(1f),
                        enabled = !state.isRefreshing && !state.isClearing
                    ) {
                        Text(if (state.isClearing) "Clearing..." else "Clear")
                    }
                    Button(
                        onClick = { onCopyAll(combinedLog) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Copy")
                    }
                }

                state.lastError?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp, max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DebugLogSection(title = "Local Logs", lines = state.localLogs)
                    DebugLogSection(title = "Server Logs", lines = state.serverLogs)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun DebugLogSection(title: String, lines: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall
        )
        if (lines.isEmpty()) {
            Text(
                text = "(empty)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = lines.joinToString(separator = "\n"),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
