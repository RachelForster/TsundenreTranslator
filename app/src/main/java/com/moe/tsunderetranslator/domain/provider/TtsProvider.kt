package com.moe.tsunderetranslator.domain.provider

interface TtsProvider {
    suspend fun speak(
        baseUrl: String,
        text: String,
        options: Map<String, Any?> = emptyMap(),
        onEvent: ((String) -> Unit)? = null
    ): Result<Unit>

    suspend fun switchModel(baseUrl: String, modelInfo: Map<String, Any>): Result<Unit>

    suspend fun fetchDebugLogs(baseUrl: String, limit: Int = 200): Result<List<String>>

    suspend fun clearDebugLogs(baseUrl: String): Result<Unit>

    fun stop()
    fun release()
}
