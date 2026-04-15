package com.moe.tsunderetranslator.domain.provider

import com.moe.tsunderetranslator.domain.model.AsrResult
import kotlinx.coroutines.flow.Flow

interface AsrProvider {
    val asrResult: Flow<AsrResult>
    fun startRecognition()
    fun stopRecognition()
    fun release()
}