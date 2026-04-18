package com.moe.tsunderetranslator.domain.provider

interface TtsProvider {
    suspend fun speak(
        baseUrl: String,
        text: String,
        options: Map<String, Any?> = emptyMap(),
        onEvent: ((String) -> Unit)? = null
    ): Result<Unit>

    suspend fun switchModel(baseUrl: String, modelInfo: Map<String, Any>): Result<Unit>

    fun stop()
    fun release()
}
