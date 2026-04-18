package com.moe.tsunderetranslator.domain.provider

interface TtsProvider {
    /** 播放语音 */
    fun speak(text: String, options: Map<String, Any?> = emptyMap())

    /** * 通用的模型切换接口
     * @param modelInfo 包含模型信息的键值对。
     * 例如 GPT-SoVITS 传 ["gpt_weights": "...", "sovits_weights": "..."]
     * 例如 CosyVoice 传 ["voice": "longxiaochun"]
     */
    fun switchModel(modelInfo: Map<String, Any>)

    fun stop()
    fun release()
}