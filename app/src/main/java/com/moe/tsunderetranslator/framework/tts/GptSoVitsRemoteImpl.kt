package com.moe.tsunderetranslator.framework.tts

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.moe.tsunderetranslator.data.remote.GptSoVitsApi
import com.moe.tsunderetranslator.domain.provider.TtsProvider
import kotlinx.coroutines.*
import okhttp3.ResponseBody
import java.io.File
import java.io.InputStream

class GptSoVitsRemoteImpl(
    private val api: GptSoVitsApi,
    private val context: Context
) : TtsProvider {

    private var mediaPlayer: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // 使用 SupervisorJob 确保单个请求失败不影响整个 Scope
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 对应 Python 的 generate_speech
     */
    override fun speak(text: String, options: Map<String, Any?>) {
        // 1. 构建参数，对齐 Python 中的 params 字典
        val params = mutableMapOf<String, Any>(
            "text" to text,
            "text_lang" to (options["text_lang"] ?: "ja"),
            "ref_audio_path" to (options["ref_audio_path"] ?: ""),
            "prompt_text" to (options["prompt_text"] ?: ""),
            "prompt_lang" to (options["prompt_lang"] ?: ""),
            "text_split_method" to "cut5",
            "batch_size" to 1,
            "speed_factor" to (options["speed_factor"] ?: 1.0)
        )

        serviceScope.launch {
            try {
                val response = api.generateTts(params)
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        saveAndPlay(body.byteStream())
                    }
                } else {
                    Log.e("GptSoVits", "TTS 请求失败: ${response.errorBody()?.string()}")
                }
            } catch (e: Exception) {
                Log.e("GptSoVits", "网络异常: ${e.message}")
            }
        }
    }

    /**
     * 对应 Python 的 switch_model
     * 更加 General 的实现：根据 Key 是否存在来决定是否调用 API
     */
    override fun switchModel(modelInfo: Map<String, Any>) {
        serviceScope.launch {
            try {
                // 处理 GPT 权重切换
                val gptPath = modelInfo["gpt_model_path"] as? String
                if (!gptPath.isNullOrBlank() && gptPath.endsWith(".ckpt")) {
                    api.setGptWeights(gptPath)
                    Log.d("GptSoVits", "GPT 模型切换完成: $gptPath")
                }

                // 处理 SoVITS 权重切换
                val sovitsPath = modelInfo["sovits_model_path"] as? String
                if (!sovitsPath.isNullOrBlank() && sovitsPath.endsWith(".pth")) {
                    api.setSovitsWeights(sovitsPath)
                    Log.d("GptSoVits", "SoVITS 模型切换完成: $sovitsPath")
                }
            } catch (e: Exception) {
                Log.e("GptSoVits", "模型切换网络异常: ${e.message}")
            }
        }
    }

    /**
     * 将二进制流保存到缓存并播放
     */
    private fun saveAndPlay(inputStream: InputStream) {
        try {
            // 创建临时缓存文件 (对应 Python 的 file_path 逻辑)
            val tempFile = File(context.cacheDir, "gpt_sovits_temp.wav")
            tempFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }

            // 回到主线程操作 MediaPlayer
            mainHandler.post {
                stop()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(tempFile.absolutePath)
                    prepare()
                    start()
                    // 播放完成后回调（可选）
                    setOnCompletionListener {
                        Log.d("GptSoVits", "播放完成")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GptSoVits", "播放处理失败: ${e.message}")
        }
    }

    override fun stop() {
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null
    }

    override fun release() {
        stop()
        serviceScope.cancel() // 清理所有未完成的协程
    }
}