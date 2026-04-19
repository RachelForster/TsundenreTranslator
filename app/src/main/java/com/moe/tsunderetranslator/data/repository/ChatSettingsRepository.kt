package com.moe.tsunderetranslator.data.repository

import android.content.SharedPreferences
import androidx.core.content.edit
import com.moe.tsunderetranslator.domain.model.LlmSettings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatSettingsRepository @Inject constructor(
    private val sharedPreferences: SharedPreferences
) {
    fun loadSettings(): LlmSettings {
        return LlmSettings(
            baseUrl = sharedPreferences.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL,
            model = sharedPreferences.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
            apiKey = sharedPreferences.getString(KEY_API_KEY, "") ?: "",
            ttsBaseUrl = sharedPreferences.getString(KEY_TTS_BASE_URL, DEFAULT_TTS_BASE_URL)
                ?: DEFAULT_TTS_BASE_URL,
            ttsCharacterName = sharedPreferences.getString(KEY_TTS_CHARACTER_NAME, "") ?: "",
            ttsRefAudioPath = sharedPreferences.getString(KEY_TTS_REF_AUDIO_PATH, "") ?: ""
        )
    }

    fun saveSettings(settings: LlmSettings) {
        sharedPreferences.edit {
            putString(KEY_BASE_URL, settings.baseUrl.trim())
                .putString(KEY_MODEL, settings.model.trim())
                .putString(KEY_API_KEY, settings.apiKey.trim())
                .putString(KEY_TTS_BASE_URL, settings.ttsBaseUrl.trim())
                .putString(KEY_TTS_CHARACTER_NAME, settings.ttsCharacterName.trim())
                .putString(KEY_TTS_REF_AUDIO_PATH, settings.ttsRefAudioPath.trim())
        }
    }

    private companion object {
        const val KEY_BASE_URL = "llm.base_url"
        const val KEY_MODEL = "llm.model"
        const val KEY_API_KEY = "llm.api_key"
        const val KEY_TTS_BASE_URL = "tts.base_url"
        const val KEY_TTS_CHARACTER_NAME = "tts.character_name"
        const val KEY_TTS_REF_AUDIO_PATH = "tts.ref_audio_path"
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-chat"
        const val DEFAULT_TTS_BASE_URL = "http://192.168.1.100:9880/"
    }
}
