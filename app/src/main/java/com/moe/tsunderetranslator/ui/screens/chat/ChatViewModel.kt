package com.moe.tsunderetranslator.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moe.tsunderetranslator.data.repository.AsrRepository
import com.moe.tsunderetranslator.data.repository.ChatRepository
import com.moe.tsunderetranslator.data.repository.ChatSettingsRepository
import com.moe.tsunderetranslator.domain.model.ChatMessage
import com.moe.tsunderetranslator.domain.model.ChatRole
import com.moe.tsunderetranslator.domain.model.LlmSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val isSending: Boolean = false,
    val isRecording: Boolean = false,
    val asrStatusText: String = "",
    val errorMessage: String? = null,
    val settings: LlmSettings = LlmSettings()
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val chatSettingsRepository: ChatSettingsRepository,
    private val asrRepository: AsrRepository
) : ViewModel() {

    private val settingsFlow = MutableStateFlow(chatSettingsRepository.loadSettings())
    private val messagesFlow = MutableStateFlow(chatRepository.loadMessages())
    private val inputFlow = MutableStateFlow("")
    private val isSendingFlow = MutableStateFlow(false)
    private val isRecordingFlow = MutableStateFlow(false)
    private val asrStatusFlow = MutableStateFlow("")
    private val errorFlow = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ChatUiState> = combine(
        messagesFlow,
        inputFlow,
        isSendingFlow,
        isRecordingFlow,
        asrStatusFlow,
        errorFlow,
        settingsFlow
    ) { values ->
        ChatUiState(
            messages = values[0] as List<ChatMessage>,
            input = values[1] as String,
            isSending = values[2] as Boolean,
            isRecording = values[3] as Boolean,
            asrStatusText = values[4] as String,
            errorMessage = values[5] as String?,
            settings = values[6] as LlmSettings
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ChatUiState(settings = settingsFlow.value)
    )

    init {
        viewModelScope.launch {
            asrRepository.currentText.collect { result ->
                asrStatusFlow.value = result.text
                if (result.isFinal && result.text.isNotBlank()) {
                    inputFlow.value = result.text
                    isRecordingFlow.value = false
                }
            }
        }
    }

    fun updateInput(value: String) {
        inputFlow.value = value
    }

    fun dismissError() {
        errorFlow.value = null
    }

    fun saveSettings(baseUrl: String, model: String, apiKey: String) {
        val settings = LlmSettings(
            baseUrl = baseUrl.ifBlank { "https://api.deepseek.com" },
            model = model.ifBlank { "deepseek-chat" },
            apiKey = apiKey
        )
        chatSettingsRepository.saveSettings(settings)
        settingsFlow.value = settings
    }

    fun toggleAsr() {
        val nextRecording = !isRecordingFlow.value
        isRecordingFlow.value = nextRecording
        if (nextRecording) {
            asrRepository.start()
            asrStatusFlow.value = "正在启动语音输入..."
        } else {
            asrRepository.stop()
            asrStatusFlow.value = "语音输入已停止"
        }
    }

    fun sendMessage() {
        if (isSendingFlow.value) return

        val text = inputFlow.value.trim()
        val settings = settingsFlow.value

        when {
            text.isBlank() -> {
                errorFlow.value = "请输入消息内容"
                return
            }
            settings.apiKey.isBlank() -> {
                errorFlow.value = "请先在设置中填写 API Key"
                return
            }
            settings.model.isBlank() -> {
                errorFlow.value = "请先在设置中填写模型名称"
                return
            }
        }

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatRole.User,
            content = text
        )
        val assistantMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatRole.Assistant,
            content = ""
        )

        val updatedMessages = messagesFlow.value + userMessage + assistantMessage
        messagesFlow.value = updatedMessages
        chatRepository.saveMessages(updatedMessages)
        inputFlow.value = ""
        errorFlow.value = null
        isSendingFlow.value = true

        viewModelScope.launch {
            try {
                chatRepository.streamChatCompletion(
                    settings = settings,
                    messages = updatedMessages.dropLast(1)
                ) { chunk ->
                    val current = messagesFlow.value.map { message ->
                        if (message.id == assistantMessage.id) {
                            message.copy(content = message.content + chunk)
                        } else {
                            message
                        }
                    }
                    messagesFlow.value = current
                    chatRepository.saveMessages(current)
                }
            } catch (e: Exception) {
                val current = messagesFlow.value.map { message ->
                    if (message.id == assistantMessage.id && message.content.isBlank()) {
                        message.copy(content = "请求失败：${e.message ?: "未知错误"}")
                    } else {
                        message
                    }
                }
                messagesFlow.value = current
                chatRepository.saveMessages(current)
                errorFlow.value = e.message ?: "请求失败"
            } finally {
                isSendingFlow.value = false
            }
        }
    }
}
