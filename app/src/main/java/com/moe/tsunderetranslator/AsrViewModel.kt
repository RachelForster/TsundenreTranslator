package com.moe.tsunderetranslator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moe.tsunderetranslator.data.repository.AsrRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AsrViewModel @Inject constructor(
    private val repository: AsrRepository
) : ViewModel() {
    // 将 Repository 的 Flow 转化为 Compose 可观察的 State
    val asrText = repository.currentText
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun toggleAsr(isRecording: Boolean) {
        if (isRecording) {
            repository.stop()
        } else {
            repository.start()
        }
    }
}