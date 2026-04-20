package com.moe.tsunderetranslator.framework.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.moe.tsunderetranslator.data.remote.GptSoVitsApi
import com.moe.tsunderetranslator.data.repository.ChatSettingsRepository
import com.moe.tsunderetranslator.domain.provider.TtsProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GptSoVitsProviderImpl 实现了 TtsProvider 接口
 * 负责与 GPT-SoVITS API 交互并管理音频播放。
 */
@Singleton
class GptSoVitsImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : TtsProvider {

    private var activeApi: GptSoVitsApi? = null
    private var lastBaseUrl: String? = null
    private var mediaPlayer: MediaPlayer? = null

    companion object {
        private const val TAG = "GptSoVitsProvider"
        private const val TEMP_FILE_NAME = "gpt_sovits_output.wav"
    }

    private fun getApi(baseUrl: String): GptSoVitsApi {
        val formattedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        if (activeApi == null || lastBaseUrl != formattedUrl) {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(formattedUrl)
                .client(client)
                // 关键：添加这行来处理 @Body Map 转换
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            activeApi = retrofit.create(GptSoVitsApi::class.java)
            lastBaseUrl = formattedUrl
        }
        return activeApi!!
    }

    override suspend fun speak(
        baseUrl: String,
        text: String,
        options: Map<String, Any?>,
        onEvent: ((String) -> Unit)?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            onEvent?.invoke("connecting")
            val api = getApi(baseUrl)

            // 映射请求参数，逻辑参考 tts_adapter.py
            val params = mapOf(
                "text" to text,
                "text_lang" to (options["text_lang"] ?: "zh"),
                "ref_audio_path" to (options["ref_audio_path"] ?: "C:\\AI\\customize\\EasyAIDesktopAssistant\\data\\models\\komaeda\\komaeda01.mp3_0000204800_0000416320.wav"),
                "prompt_text" to (options["prompt_text"] ?: "だからって放置するわけにもいかないよね。あのゲームは今回の動機なんだからさ。"),
                "prompt_lang" to (options["prompt_lang"] ?: "ja"),
                "text_split_method" to (options["text_split_method"] ?: "cut5"),
                "batch_size" to (options["batch_size"] ?: 1),
                "speed_factor" to (options["speed_factor"] ?: 1.0)
            )
            Log.d("SSS", params.toString());

            onEvent?.invoke("generating")
            val response = api.generateTts(params) // 调用 API 生成音频

            if (response.isSuccessful) {
                val body = response.body() ?: throw Exception("Response body is null")

                // 将响应流保存到缓存目录中的临时文件
                val tempFile = File(context.cacheDir, TEMP_FILE_NAME)
                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                onEvent?.invoke("playing")
                playAudioFile(tempFile)
                Result.success(Unit)
            } else {
                val errorMsg = "API Error: ${response.code()} - ${response.message()}"
                Log.e(TAG, errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS Generation failed", e)
            Result.failure(e)
        }
    }

    /**
     * 切换 GPT-SoVITS 模型权重
     */
    override suspend fun switchModel(baseUrl: String, modelInfo: Map<String, Any>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val api = getApi(baseUrl)

            // 处理 GPT 模型权重切换
            (modelInfo["gpt_model_path"] as? String)?.let { path ->
                if (path.isNotBlank()) {
                    api.setGptWeights(path)
                }
            }

            // 处理 SoVITS 模型权重切换
            (modelInfo["sovits_model_path"] as? String)?.let { path ->
                if (path.isNotBlank()) {
                    api.setSovitsWeights(path)
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Model switch failed", e)
            Result.failure(e)
        }
    }

    /**
     * 使用 MediaPlayer 播放生成的本地音频文件
     */
    private suspend fun playAudioFile(file: File) = withContext(Dispatchers.Main) {
        stop() // 播放新音频前停止旧音频

        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setDataSource(file.absolutePath)
            prepare()
            start()

            // 播放完成后释放资源（可选，取决于是否需要频繁复用播放器）
            setOnCompletionListener {
                it.release()
                if (mediaPlayer == it) mediaPlayer = null
            }
        }
    }

    /**
     * 停止当前正在播放的音频
     */
    override fun stop() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "Stop playback failed", e)
        }
    }

    /**
     * 释放所有资源
     */
    override fun release() {
        stop()
        activeApi = null
        lastBaseUrl = null
    }
}