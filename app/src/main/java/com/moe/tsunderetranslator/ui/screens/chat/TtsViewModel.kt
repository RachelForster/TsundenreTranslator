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
    private val _errors = MutableSharedFlow<String>()
    val errors = _errors.asSharedFlow()

    private val _events = MutableSharedFlow<String>()
    val events = _events.asSharedFlow()

    fun speakText(text: String) {
        val settings = chatSettingsRepository.loadSettings()
        val options = mapOf(
            "character_name" to settings.ttsCharacterName,
            "text_lang" to "zh",
            "speed_factor" to 1.0,
            "ref_audio_path" to settings.ttsRefAudioPath,
            "prompt_text" to "",
            "prompt_lang" to ""
        )

        viewModelScope.launch {
            _events.emit("TTS request sent")
            val result = repository.speak(text, options) { message ->
                viewModelScope.launch {
                    _events.emit(message)
                }
            }
            result.exceptionOrNull()?.message?.let { message ->
                _errors.emit(message)
            }
        }
    }
}
