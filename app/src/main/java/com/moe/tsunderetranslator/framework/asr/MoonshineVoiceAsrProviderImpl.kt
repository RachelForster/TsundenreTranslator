package com.moe.tsunderetranslator.framework.asr

import ai.moonshine.voice.JNI
import ai.moonshine.voice.MicTranscriber
import ai.moonshine.voice.TranscriptEvent
import ai.moonshine.voice.TranscriptEventListener
import android.content.Context
import android.util.Log
import com.moe.tsunderetranslator.domain.model.AsrResult
import com.moe.tsunderetranslator.domain.provider.AsrProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MoonshineVoiceAsrProviderImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : AsrProvider {

    companion object {
        private const val TAG = "MoonshineAsr"
        private val MODEL_FILES = listOf(
            "encoder_model.ort",
            "decoder_model_merged.ort",
            "tokenizer.bin"
        )
        private val modelAssetDir: String = "moonshine-zh"
        // 默认使用流式标准模型架构
        private val modelArch: Int = JNI.MOONSHINE_MODEL_ARCH_BASE
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _asrResult = MutableSharedFlow<AsrResult>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val asrResult: Flow<AsrResult> = _asrResult.asSharedFlow()

    private var transcriber: MicTranscriber? = null

    @Volatile
    private var isPrepared = false

    /**
     * 初始化：拷贝模型并配置监听器
     */
    private fun prepareTranscriber() {
        if (isPrepared) return

        try {
            // 1. 确保模型在磁盘上（因为 loadFromFiles 需要真实路径）
            val modelPath = ensureModelOnDisk()
            Log.i(TAG, "Model path: $modelPath")

            // 2. 实例化
            val mic = MicTranscriber()

            // 3. 核心：实现 Visitor 模式监听器
            mic.addListener { event ->
                event.accept(object : TranscriptEventListener() {
                    // 中间结果（正在说话中）
                    override fun onLineTextChanged(e: TranscriptEvent.LineTextChanged) {
                        val text = e.line?.text ?: ""
                        emitResult(text, isFinal = false)
                    }

                    override fun onLineUpdated(event: TranscriptEvent.LineUpdated) {
                        val text = event.line?.text?: ""
                        emitResult(text, isFinal = false)
                    }

                    // 最终结果（一句话结束）
                    override fun onLineCompleted(e: TranscriptEvent.LineCompleted) {
                        val text = e.line?.text ?: ""
                        if (text.isNotBlank()) {
                            emitResult(text, isFinal = true)
                        }
                    }
                })
            }

            // 4. 加载模型
            mic.loadFromFiles(modelPath, modelArch)
            this.transcriber = mic
            isPrepared = true
            Log.d(TAG, "Moonshine transcriber prepared successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare Moonshine: ${e.message}", e)
        }
    }

    override fun startRecognition() {
        scope.launch {
            if (!isPrepared) {
                prepareTranscriber()
            }

            transcriber?.let {
                // 必须调用此方法，否则内部 CompletableFuture 不会完成，线程不会启动
                it.onMicPermissionGranted()

                try {
                    it.start()
                    Log.i(TAG, "ASR Recognition started.")
                } catch (e: Exception) {
                    Log.e(TAG, "Start recognition failed", e)
                }
            }
        }
    }

    override fun stopRecognition() {
        try {
            transcriber?.stop()
            Log.i(TAG, "ASR Recognition stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "Stop recognition error", e)
        }
    }

    override fun release() {
        stopRecognition()
        // MicTranscriber 源码中没有显式的 release，
        // 但建议置空以利于 GC，或者如果基类有相关方法请补上
        transcriber = null
        isPrepared = false
    }

    /**
     * 内部辅助：发送结果到 Flow
     */
    private fun emitResult(text: String, isFinal: Boolean) {
        _asrResult.tryEmit(
            AsrResult(
                text = text,
                isFinal = isFinal,
                confidence = if (isFinal) 1.0f else 0.0f
            )
        )
    }

    /**
     * 内部辅助：将 Assets 里的模型文件拷贝到 App 私有目录
     */
    private fun ensureModelOnDisk(): String {
        val modelDir = File(context.filesDir, modelAssetDir)
        if (!modelDir.exists()) modelDir.mkdirs()

        MODEL_FILES.forEach { fileName ->
            val dest = File(modelDir, fileName)
            // 如果文件不存在或者大小为0，则拷贝
            if (!dest.exists() || dest.length() == 0L) {
                Log.d(TAG, "Copying $fileName to internal storage...")
                context.assets.open("$modelAssetDir/$fileName").use { input ->
                    dest.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
        return modelDir.absolutePath
    }
}