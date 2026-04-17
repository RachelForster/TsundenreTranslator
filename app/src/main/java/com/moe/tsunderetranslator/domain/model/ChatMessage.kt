package com.moe.tsunderetranslator.domain.model

data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val content: String
)

enum class ChatRole(val apiValue: String) {
    System("system"),
    User("user"),
    Assistant("assistant")
}
