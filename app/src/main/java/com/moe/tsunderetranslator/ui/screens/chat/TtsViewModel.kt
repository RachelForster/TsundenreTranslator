package com.moe.tsunderetranslator.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moe.tsunderetranslator.data.repository.ChatSettingsRepository
import com.moe.tsunderetranslator.data.repository.TtsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

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

    fun speakText(text: String) {
        val normalizedText = text.trim()
        if (normalizedText.isBlank()) {
            return
        }

        if (!inFlightTexts.add(normalizedText)) {
            viewModelScope.launch {
                _events.emit("Duplicate TTS request dropped")
            }
            return
        }

        val settings = chatSettingsRepository.loadSettings()
        val options = mapOf(
            "character_name" to settings.ttsCharacterName,
            "text_lang" to "zh",
            "speed_factor" to 1.0,
            "ref_audio_path" to settings.ttsRefAudioPath,
            "prompt_text" to "だからって放置するわけにもいかないよね。あのゲームは今回の動機なんだからさ。",
            "prompt_lang" to "ja"
        )

        viewModelScope.launch {
            _events.emit("TTS request sent")
            try {
                val result = repository.speak(normalizedText, options) { message ->
                    viewModelScope.launch {
                        _events.emit(message)
                    }
                }
                result.exceptionOrNull()?.message?.let { message ->
                    _errors.emit(message)
                }
            } finally {
                inFlightTexts.remove(normalizedText)
            }
        }
    }
}
