package com.moe.tsunderetranslator.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moe.tsunderetranslator.data.repository.ChatSettingsRepository
import com.moe.tsunderetranslator.data.repository.TtsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TtsDebugState(
    val localLogs: List<String> = emptyList(),
    val serverLogs: List<String> = emptyList(),
    val isRefreshing: Boolean = false,
    val isClearing: Boolean = false,
    val lastError: String? = null
)

@HiltViewModel
class TtsViewModel @Inject constructor(
    private val repository: TtsRepository,
    private val chatSettingsRepository: ChatSettingsRepository
) : ViewModel() {
    private val inFlightTexts = mutableSetOf<String>()

    private val _errors = MutableSharedFlow<String>()
    val errors = _errors.asSharedFlow()

    private val _events = MutableSharedFlow<String>()
    val events = _events.asSharedFlow()

    private val _debugState = MutableStateFlow(TtsDebugState())
    val debugState = _debugState.asStateFlow()

    fun speakText(text: String) {
        val normalizedText = text.trim()
        if (normalizedText.isBlank()) {
            return
        }

        if (!inFlightTexts.add(normalizedText)) {
            viewModelScope.launch {
                publishEvent("Duplicate TTS request dropped")
            }
            return
        }

        viewModelScope.launch {
            try {
                val settings = chatSettingsRepository.loadSettings()
                val options = mapOf(
                    "character_name" to settings.ttsCharacterName,
                    "text_lang" to "zh",
                    "speed_factor" to 1.0,
                    "ref_audio_path" to settings.ttsRefAudioPath,
                    "prompt_text" to "",
                    "prompt_lang" to ""
                )
                publishEvent("TTS request sent")
                val result = repository.speak(normalizedText, options) { message ->
                    viewModelScope.launch {
                        publishEvent(message)
                    }
                }
                result.exceptionOrNull()?.message?.let { message ->
                    appendLocalLog("ERROR: $message")
                    _errors.emit(message)
                }
            } finally {
                inFlightTexts.remove(normalizedText)
            }
        }
    }

    fun refreshDebugLogs() {
        viewModelScope.launch {
            _debugState.value = _debugState.value.copy(isRefreshing = true, lastError = null)
            repository.fetchDebugLogs().fold(
                onSuccess = { logs ->
                    _debugState.value = _debugState.value.copy(
                        serverLogs = logs,
                        isRefreshing = false,
                        lastError = null
                    )
                },
                onFailure = { error ->
                    val message = error.message ?: "Failed to fetch debug logs"
                    appendLocalLog("ERROR: $message")
                    _debugState.value = _debugState.value.copy(
                        isRefreshing = false,
                        lastError = message
                    )
                }
            )
        }
    }

    fun clearDebugLogs() {
        viewModelScope.launch {
            _debugState.value = _debugState.value.copy(isClearing = true, lastError = null)
            repository.clearDebugLogs().fold(
                onSuccess = {
                    _debugState.value = _debugState.value.copy(
                        localLogs = emptyList(),
                        serverLogs = emptyList(),
                        isClearing = false,
                        lastError = null
                    )
                    publishEvent("TTS debug logs cleared")
                    refreshDebugLogs()
                },
                onFailure = { error ->
                    val message = error.message ?: "Failed to clear debug logs"
                    appendLocalLog("ERROR: $message")
                    _debugState.value = _debugState.value.copy(
                        isClearing = false,
                        lastError = message
                    )
                }
            )
        }
    }

    private suspend fun publishEvent(message: String) {
        appendLocalLog(message)
        _events.emit(message)
    }

    private fun appendLocalLog(message: String) {
        _debugState.value = _debugState.value.copy(
            localLogs = (_debugState.value.localLogs + message).takeLast(200)
        )
    }
}
