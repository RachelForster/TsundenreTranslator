package com.moe.tsunderetranslator.ui.screens.chat

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.moe.tsunderetranslator.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel, asrViewModel: AsrViewModel, ttsViewModel: TtsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val asrText by asrViewModel.uiText.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }

    // 处理错误消息
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tsundere Translator") },
                navigationIcon = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(painterResource(android.R.drawable.ic_menu_manage), "Settings")
                    }
                }
            )
        },
        bottomBar = {
            ChatInputBar(
                input = uiState.input,
                asrText = asrText,
                isRecording = isRecording,
                isSending = uiState.isSending,
                onValueChange = {
                    viewModel.updateInput(it)
                    asrViewModel.setInput(it)
                },
                onVoiceToggle = {
                    isRecording = !isRecording
                    if (isRecording){
                        asrViewModel.setInput(uiState.input)
                    }
                    else (
                        viewModel.updateInput(asrText)
                    )
                    asrViewModel.toggleAsr(isRecording)
                                },
                onSendClick = {
                    asrViewModel.clearInput()
                    viewModel.sendMessage()
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        MessageList(
            modifier = Modifier.padding(padding),
            messages = uiState.messages,
            isSending = uiState.isSending,
            onTtsClick = ttsViewModel::speakText
        )
    }

    if (showSettings) {
        SettingsDialog(
            initialBaseUrl = uiState.settings.baseUrl,
            initialModel = uiState.settings.model,
            initialApiKey = uiState.settings.apiKey,
            onDismiss = { showSettings = false },
            onSave = { b, m, a ->
                viewModel.saveSettings(b, m, a)
                showSettings = false
            }
        )
    }
}