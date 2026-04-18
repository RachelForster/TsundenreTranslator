package com.moe.tsunderetranslator.ui.screens.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import com.moe.tsunderetranslator.ui.components.ChatInputBar
import com.moe.tsunderetranslator.ui.components.MessageList
import com.moe.tsunderetranslator.ui.components.SettingsDialog
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel, asrViewModel: AsrViewModel, ttsViewModel: TtsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val asrText by asrViewModel.uiText.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }

    suspend fun showMessage(message: String) {
        snackbarHostState.showSnackbar(message)
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            showMessage(it)
            viewModel.dismissError()
        }
    }

    LaunchedEffect(ttsViewModel) {
        ttsViewModel.errors.collectLatest { message ->
            showMessage(message)
        }
    }

    LaunchedEffect(ttsViewModel) {
        ttsViewModel.events.collectLatest { message ->
            showMessage(message)
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
                    if (isRecording) {
                        asrViewModel.setInput(uiState.input)
                    } else {
                        viewModel.updateInput(asrText)
                    }
                    asrViewModel.toggleAsr(isRecording)
                },
                onSendClick = {
                    asrViewModel.clearInput()
                    viewModel.sendMessage()
                }
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    modifier = Modifier.clickable {
                        clipboardManager.setText(AnnotatedString(data.visuals.message))
                    }
                ) {
                    Text(data.visuals.message)
                }
            }
        }
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
            initialTtsBaseUrl = uiState.settings.ttsBaseUrl,
            initialTtsCharacterName = uiState.settings.ttsCharacterName,
            initialTtsRefAudioPath = uiState.settings.ttsRefAudioPath,
            onDismiss = { showSettings = false },
            onSave = { b, m, a, t, c, r ->
                viewModel.saveSettings(b, m, a, t, c, r)
                showSettings = false
            }
        )
    }
}
