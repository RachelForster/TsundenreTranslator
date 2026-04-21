package com.moe.tsunderetranslator.data.repository

import com.moe.tsunderetranslator.domain.provider.TtsProvider
import javax.inject.Inject

class TtsRepository @Inject constructor(
    private val ttsProvider: TtsProvider,
    private val chatSettingsRepository: ChatSettingsRepository
) {
    suspend fun speak(
        text: String,
        options: Map<String, Any?>,
        onEvent: ((String) -> Unit)? = null
    ): Result<Unit> {
        val settings = chatSettingsRepository.loadSettings()
        return ttsProvider.speak(settings.ttsBaseUrl, text, options, onEvent)
    }

    suspend fun switchModel(modelInfo: Map<String, Any>): Result<Unit> {
        val settings = chatSettingsRepository.loadSettings()
        return ttsProvider.switchModel(settings.ttsBaseUrl, modelInfo)
    }

    suspend fun fetchDebugLogs(limit: Int = 200): Result<List<String>> {
        val settings = chatSettingsRepository.loadSettings()
        return ttsProvider.fetchDebugLogs(settings.ttsBaseUrl, limit)
    }

    suspend fun clearDebugLogs(): Result<Unit> {
        val settings = chatSettingsRepository.loadSettings()
        return ttsProvider.clearDebugLogs(settings.ttsBaseUrl)
    }

    fun stop() = ttsProvider.stop()
}
