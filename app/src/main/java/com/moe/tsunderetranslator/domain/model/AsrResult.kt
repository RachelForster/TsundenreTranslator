package com.moe.tsunderetranslator.domain.model

data class AsrResult(
    val text: String,
    val isFinal: Boolean = false,
    val confidence: Float = 1.0f
)

// domain/model/AsrStatus.kt
sealed class AsrStatus {
    object Idle : AsrStatus()
    object Recording : AsrStatus()
    data class Error(val message: String) : AsrStatus()
}