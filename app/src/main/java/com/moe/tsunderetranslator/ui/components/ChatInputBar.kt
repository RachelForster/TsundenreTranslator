package com.moe.tsunderetranslator.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@Composable
fun ChatInputBar(
    input: String,
    asrText: String,
    isRecording: Boolean,
    isSending: Boolean,
    onValueChange: (String) -> Unit,
    onVoiceToggle: () -> Unit,
    onSendClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        if (isRecording) {
            Text(
                text = "语音输入中",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                // 如果在录音，显示 ASR 识别的文本，否则显示手动输入的文本
                value = if (isRecording) asrText else input,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message") },
                shape = RoundedCornerShape(50),
                minLines = 1,
                maxLines = 5
            )

            // 语音按钮
            IconButton(
                onClick = { onVoiceToggle() },
                modifier = Modifier.size(48.dp),
                colors = if (isRecording) {
                    IconButtonDefaults.filledIconButtonColors()
                } else {
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                }
            ) {
                Icon(
                    painter = painterResource(
                        android.R.drawable.ic_btn_speak_now
                    ),
                    contentDescription = "Voice Input"
                )
            }

            // 发送按钮
            FilledIconButton(
                onClick = onSendClick,
                enabled = !isSending && (input.isNotBlank() || asrText.isNotBlank()),
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    painter = painterResource(android.R.drawable.ic_menu_send),
                    contentDescription = "Send"
                )
            }
        }
    }
}