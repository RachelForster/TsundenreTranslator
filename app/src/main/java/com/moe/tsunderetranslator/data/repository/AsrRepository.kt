package com.moe.tsunderetranslator.data.repository

import com.moe.tsunderetranslator.domain.provider.AsrProvider
import javax.inject.Inject

class AsrRepository @Inject constructor(
    private val asrProvider: AsrProvider
) {
    val currentText = asrProvider.asrResult

    fun start() = asrProvider.startRecognition()
    fun stop() = asrProvider.stopRecognition()
}