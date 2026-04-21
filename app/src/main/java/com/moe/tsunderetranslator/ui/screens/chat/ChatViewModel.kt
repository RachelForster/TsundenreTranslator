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

    private val settingsFlow = chatSettingsRepository.settingsFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        LlmSettings()
    )
    private val messagesFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
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
            messagesFlow.value = chatRepository.loadMessages()
        }

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

    fun saveSettings(
        baseUrl: String,
        model: String,
        apiKey: String,
        ttsBaseUrl: String,
        ttsCharacterName: String,
        ttsRefAudioPath: String
    ) {
        val settings = LlmSettings(
            baseUrl = baseUrl.ifBlank { "https://api.deepseek.com" },
            model = model.ifBlank { "deepseek-chat" },
            apiKey = apiKey,
            ttsBaseUrl = ttsBaseUrl.ifBlank { "http://192.168.1.100:9880/" },
            ttsCharacterName = ttsCharacterName,
            ttsRefAudioPath = ttsRefAudioPath
        )
        viewModelScope.launch {
            chatSettingsRepository.saveSettings(settings)
        }
    }

    fun toggleAsr() {
        val nextRecording = !isRecordingFlow.value
        isRecordingFlow.value = nextRecording
        if (nextRecording) {
            asrRepository.start()
            asrStatusFlow.value = "濮濓絽婀崥顖氬З鐠囶參鐓舵潏鎾冲弳..."
        } else {
            asrRepository.stop()
            asrStatusFlow.value = "鐠囶參鐓舵潏鎾冲弳瀹告彃浠犲?"
        }
    }

    fun sendMessage() {
        if (isSendingFlow.value) return

        val text = inputFlow.value.trim()
        val settings = settingsFlow.value

        when {
            text.isBlank() -> {
                errorFlow.value = "鐠囩柉绶崗銉︾Х閹垰鍞寸€?"
                return
            }
            settings.apiKey.isBlank() -> {
                errorFlow.value = "鐠囧嘲鍘涢崷銊啎缂冾喕鑵戞繅顐㈠晸 API Key"
                return
            }
            settings.model.isBlank() -> {
                errorFlow.value = "鐠囧嘲鍘涢崷銊啎缂冾喕鑵戞繅顐㈠晸濡€崇€烽崥宥囆?"
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
                        message.copy(content = "鐠囬攱鐪版径杈Е閿?{e.message ?: \"閺堫亞鐓￠柨娆掝嚖\"}")
                    } else {
                        message
                    }
                }
                messagesFlow.value = current
                chatRepository.saveMessages(current)
                errorFlow.value = e.message ?: "鐠囬攱鐪版径杈Е"
            } finally {
                isSendingFlow.value = false
            }
        }
    }
}
