package com.moe.tsunderetranslator.framework.asr

import com.moe.tsunderetranslator.domain.model.AsrResult
import com.moe.tsunderetranslator.domain.provider.AsrProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WenetAsrImpl @Inject constructor(
    // 如果是本地模型，这里需要传入 Context 用于资产管理
) : AsrProvider {

    private val _asrResult = MutableStateFlow(AsrResult(""))
    override val asrResult = _asrResult.asStateFlow()

    // 假设你使用 WeNet 的官方 JNI 库
    // private var client: WenetClient? = null

    override fun startRecognition() {
        // 1. 初始化录音机 (AudioRecord)
        // 2. 建立 WeNet 推理流
        // 3. 将音频字节流送入引擎
        _asrResult.value = AsrResult("正在倾听中...", isFinal = false)
    }

    override fun stopRecognition() {
        // 停止录音并结束推理
    }

    override fun release() {
        // 释放引擎内存资源
    }
}