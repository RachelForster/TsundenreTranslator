package com.moe.tsunderetranslator.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.moe.tsunderetranslator.domain.model.LlmSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

@Singleton
class ChatSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    val settingsFlow: Flow<LlmSettings> = dataStore.data
        .catch { exception ->
            if (exception !is IOException) throw exception
            emit(emptyPreferences())
        }
        .map { preferences ->
            LlmSettings(
                baseUrl = preferences[KEY_BASE_URL]?.ifBlank { DEFAULT_BASE_URL } ?: DEFAULT_BASE_URL,
                model = preferences[KEY_MODEL]?.ifBlank { DEFAULT_MODEL } ?: DEFAULT_MODEL,
                apiKey = preferences[KEY_API_KEY].orEmpty(),
                ttsBaseUrl = preferences[KEY_TTS_BASE_URL]?.ifBlank { DEFAULT_TTS_BASE_URL }
                    ?: DEFAULT_TTS_BASE_URL,
                ttsCharacterName = preferences[KEY_TTS_CHARACTER_NAME].orEmpty(),
                ttsRefAudioPath = preferences[KEY_TTS_REF_AUDIO_PATH].orEmpty()
            )
        }

    suspend fun loadSettings(): LlmSettings = settingsFlow.first()

    suspend fun saveSettings(settings: LlmSettings) {
        dataStore.edit { preferences ->
            preferences[KEY_BASE_URL] = settings.baseUrl.trim()
            preferences[KEY_MODEL] = settings.model.trim()
            preferences[KEY_API_KEY] = settings.apiKey.trim()
            preferences[KEY_TTS_BASE_URL] = settings.ttsBaseUrl.trim()
            preferences[KEY_TTS_CHARACTER_NAME] = settings.ttsCharacterName.trim()
            preferences[KEY_TTS_REF_AUDIO_PATH] = settings.ttsRefAudioPath.trim()
        }
    }

    private companion object {
        val KEY_BASE_URL = stringPreferencesKey("llm.base_url")
        val KEY_MODEL = stringPreferencesKey("llm.model")
        val KEY_API_KEY = stringPreferencesKey("llm.api_key")
        val KEY_TTS_BASE_URL = stringPreferencesKey("tts.base_url")
        val KEY_TTS_CHARACTER_NAME = stringPreferencesKey("tts.character_name")
        val KEY_TTS_REF_AUDIO_PATH = stringPreferencesKey("tts.ref_audio_path")
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-chat"
        const val DEFAULT_TTS_BASE_URL = "http://192.168.1.100:9880/"
    }
}
