package com.moe.tsunderetranslator.domain.model

data class LlmSettings(
    val baseUrl: String = "https://api.deepseek.com",
    val model: String = "deepseek-chat",
    val apiKey: String = ""
)
