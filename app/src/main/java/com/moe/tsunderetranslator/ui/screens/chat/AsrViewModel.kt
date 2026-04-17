package com.moe.tsunderetranslator.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moe.tsunderetranslator.data.repository.AsrRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AsrViewModel @Inject constructor(
    private val repository: AsrRepository
) : ViewModel() {

    // 1. 内部变量：保存已经识别完成的历史文本
    private var historyText = ""

    // 2. 对外暴露的 UI 状态流
    private val _uiText = MutableStateFlow("")
    val uiText: StateFlow<String> = _uiText.asStateFlow()

    init {
        // 3. 订阅 Repository 的 ASR 结果并处理拼接逻辑
        viewModelScope.launch {
            repository.currentText.collect { result ->
                if (result.isFinal) {
                    // 如果识别结束，将当前结果永久存入历史
                    historyText += result.text
                    _uiText.value = historyText
                } else {
                    // 如果还在识别中，显示：历史记录 + 当前正在跳动的文字
                    _uiText.value = historyText + result.text
                }
            }
        }
    }

    fun toggleAsr(isRecording: Boolean) {
        if (isRecording) {
            repository.start()
        } else {
            repository.stop()
        }
    }
}