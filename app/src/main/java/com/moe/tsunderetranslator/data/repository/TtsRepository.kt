package com.moe.tsunderetranslator.data.repository

import com.moe.tsunderetranslator.domain.provider.TtsProvider

class TtsRepository(private val ttsProvider: TtsProvider) {

    fun speak(text: String, options: Map<String, Any?>) {
        ttsProvider.speak(text, options)
    }

    fun switchModel(modelInfo: Map<String, Any>) {
        ttsProvider.switchModel(modelInfo)
    }

    fun stop() = ttsProvider.stop()
}