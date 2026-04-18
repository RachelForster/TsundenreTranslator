package com.moe.tsunderetranslator.ui.screens.chat

import androidx.lifecycle.ViewModel
import com.moe.tsunderetranslator.data.repository.TtsRepository

class TtsViewModel(private val repository: TtsRepository) : ViewModel() {

    fun speakText(text: String) {
        // 使用你之前定义的角色配置（包含电脑端的 ref_audio_path 等）
        val options = mapOf(
            "text_lang" to "zh",
            "speed_factor" to 1.0,
            "ref_audio_path" to "C:/AI/ref/tsundere.wav", // 对应电脑路径
            "prompt_text" to "あんた、バカ？"
        )
        repository.speak(text, options)
    }
}