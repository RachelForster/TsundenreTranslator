package com.moe.tsunderetranslator.domain.model

data class LlmSettings(
    val baseUrl: String = "https://api.deepseek.com",
    val model: String = "deepseek-chat",
    val apiKey: String = "",
    val ttsBaseUrl: String = "http://192.168.1.100:9880/",
    val ttsCharacterName: String = "",
    val ttsRefAudioPath: String = ""
)
