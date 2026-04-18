package com.moe.tsunderetranslator.framework.tts

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.moe.tsunderetranslator.data.remote.GptSoVitsApi
import com.moe.tsunderetranslator.domain.provider.TtsProvider
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class GptSoVitsRemoteImpl(
    private val context: Context
) : TtsProvider {

    private var mediaPlayer: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override suspend fun speak(
        baseUrl: String,
        text: String,
        options: Map<String, Any?>,
        onEvent: ((String) -> Unit)?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(text.isNotBlank()) { "TTS text is empty" }
            require(baseUrl.isNotBlank()) { "TTS Base URL is empty" }

            val params = mutableMapOf<String, Any>(
                "character_name" to (options["character_name"] ?: ""),
                "text" to text,
                "text_lang" to (options["text_lang"] ?: "ja"),
                "ref_audio_path" to (options["ref_audio_path"] ?: ""),
                "prompt_text" to (options["prompt_text"] ?: ""),
                "prompt_lang" to (options["prompt_lang"] ?: ""),
                "text_split_method" to "cut5",
                "batch_size" to 1,
                "speed_factor" to (options["speed_factor"] ?: 1.0)
            )

            val response = createApi(baseUrl).generateTts(params)
            if (!response.isSuccessful) {
                val errorBody = response.errorBody()?.string().orEmpty()
                error(
                    "TTS request failed: HTTP ${response.code()} ${
                        errorBody.ifBlank { response.message() }
                    }"
                )
            }

            val body = response.body() ?: error("TTS response body is empty")
            onEvent?.invoke("TTS response received")
            saveAndPlay(body.byteStream(), onEvent)
        }.onFailure { e ->
            Log.e("GptSoVits", "TTS failed: ${e.message}", e)
        }
    }

    override suspend fun switchModel(baseUrl: String, modelInfo: Map<String, Any>): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(baseUrl.isNotBlank()) { "TTS Base URL is empty" }
                val api = createApi(baseUrl)

                val gptPath = modelInfo["gpt_model_path"] as? String
                if (!gptPath.isNullOrBlank() && gptPath.endsWith(".ckpt")) {
                    val response = api.setGptWeights(gptPath)
                    if (!response.isSuccessful) {
                        error("Switch GPT weights failed: HTTP ${response.code()}")
                    }
                }

                val sovitsPath = modelInfo["sovits_model_path"] as? String
                if (!sovitsPath.isNullOrBlank() && sovitsPath.endsWith(".pth")) {
                    val response = api.setSovitsWeights(sovitsPath)
                    if (!response.isSuccessful) {
                        error("Switch SoVITS weights failed: HTTP ${response.code()}")
                    }
                }
            }.onFailure { e ->
                Log.e("GptSoVits", "Switch model failed: ${e.message}", e)
            }
        }

    private fun saveAndPlay(inputStream: InputStream, onEvent: ((String) -> Unit)?) {
        try {
            val tempFile = File(context.cacheDir, "gpt_sovits_temp.wav")
            tempFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }

            val fileSizeBytes = tempFile.length()
            onEvent?.invoke("Audio received: ${fileSizeBytes / 1024} KB")
            if (fileSizeBytes <= 0L) {
                error("Generated audio file is empty")
            }

            val wavHeaderInfo = inspectWavHeader(tempFile)
            onEvent?.invoke(wavHeaderInfo.summary)
            if (!wavHeaderInfo.isValidWav) {
                onEvent?.invoke("Raw PCM detected, wrapping as WAV")
                wrapPcmAsWav(tempFile, sampleRate = 32000, channels = 1, bitsPerSample = 16)
            }

            mainHandler.post {
                try {
                    stop()
                    mediaPlayer = MediaPlayer().apply {
                        setOnPreparedListener {
                            onEvent?.invoke("Playback started")
                            start()
                        }
                        setOnCompletionListener {
                            onEvent?.invoke("Playback completed")
                            Log.d("GptSoVits", "Playback completed")
                        }
                        setOnErrorListener { _, what, extra ->
                            onEvent?.invoke("MediaPlayer error: what=$what extra=$extra")
                            true
                        }
                        setDataSource(tempFile.absolutePath)
                        prepareAsync()
                    }
                } catch (e: Exception) {
                    Log.e("GptSoVits", "Playback failed: ${e.message}", e)
                    onEvent?.invoke("Playback failed: ${e.message ?: "unknown error"}")
                }
            }
        } catch (e: Exception) {
            Log.e("GptSoVits", "Playback failed: ${e.message}", e)
            throw e
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
    }

    private fun inspectWavHeader(file: File): WavHeaderInfo {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 12) {
                return WavHeaderInfo(
                    isValidWav = false,
                    summary = "Audio header invalid: file too small (${raf.length()} bytes)"
                )
            }

            val riff = ByteArray(4)
            val wave = ByteArray(4)
            raf.readFully(riff)
            raf.seek(8)
            raf.readFully(wave)

            val riffText = riff.toAsciiText()
            val waveText = wave.toAsciiText()
            val isValid = riffText == "RIFF" && waveText == "WAVE"

            return WavHeaderInfo(
                isValidWav = isValid,
                summary = if (isValid) {
                    "WAV header OK: RIFF/WAVE"
                } else {
                    "Audio header invalid: riff='$riffText' wave='$waveText'"
                }
            )
        }
    }

    private fun ByteArray.toAsciiText(): String {
        return joinToString(separator = "") { byte ->
            val value = byte.toInt() and 0xFF
            if (value in 32..126) value.toChar().toString() else "\\x" + value.toString(16).padStart(2, '0')
        }
    }

    private fun wrapPcmAsWav(file: File, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val pcmData = file.readBytes()
        val wavHeader = createWavHeader(
            audioDataLength = pcmData.size.toLong(),
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample
        )

        FileOutputStream(file, false).use { output ->
            output.write(wavHeader)
            output.write(pcmData)
        }
    }

    private fun createWavHeader(
        audioDataLength: Long,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val chunkSize = 36 + audioDataLength

        return ByteArray(44).apply {
            writeAscii(0, "RIFF")
            writeLittleEndianInt(4, chunkSize.toInt())
            writeAscii(8, "WAVE")
            writeAscii(12, "fmt ")
            writeLittleEndianInt(16, 16)
            writeLittleEndianShort(20, 1)
            writeLittleEndianShort(22, channels.toShort())
            writeLittleEndianInt(24, sampleRate)
            writeLittleEndianInt(28, byteRate)
            writeLittleEndianShort(32, blockAlign.toShort())
            writeLittleEndianShort(34, bitsPerSample.toShort())
            writeAscii(36, "data")
            writeLittleEndianInt(40, audioDataLength.toInt())
        }
    }

    private fun ByteArray.writeAscii(offset: Int, value: String) {
        value.forEachIndexed { index, char ->
            this[offset + index] = char.code.toByte()
        }
    }

    private fun ByteArray.writeLittleEndianInt(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value shr 8) and 0xFF).toByte()
        this[offset + 2] = ((value shr 16) and 0xFF).toByte()
        this[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun ByteArray.writeLittleEndianShort(offset: Int, value: Short) {
        val intValue = value.toInt()
        this[offset] = (intValue and 0xFF).toByte()
        this[offset + 1] = ((intValue shr 8) and 0xFF).toByte()
    }

    private data class WavHeaderInfo(
        val isValidWav: Boolean,
        val summary: String
    )

    private fun createApi(baseUrl: String): GptSoVitsApi {
        val normalizedBaseUrl = baseUrl.trim().let { value ->
            when {
                value.isBlank() -> value
                value.endsWith("/") -> value
                else -> "$value/"
            }
        }

        return Retrofit.Builder()
            .baseUrl(normalizedBaseUrl)
            .client(
                OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(180, TimeUnit.SECONDS)
                    .writeTimeout(180, TimeUnit.SECONDS)
                    .callTimeout(180, TimeUnit.SECONDS)
                    .build()
            )
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GptSoVitsApi::class.java)
    }
}
