package com.moe.tsunderetranslator.framework.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.moe.tsunderetranslator.data.remote.GptSoVitsApi
import com.moe.tsunderetranslator.domain.provider.TtsProvider
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.lang.Thread.sleep
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class GptSoVitsRemoteImpl(
    private val context: Context
) : TtsProvider {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateLock = Any()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    private var mediaPlayer: MediaPlayer? = null
    private var audioTrack: AudioTrack? = null
    private var activeCall: Call? = null
    private var activeRequestId: String? = null
    private var activeBaseUrl: String? = null
    private var activeStopReason: String? = null

    override suspend fun speak(
        baseUrl: String,
        text: String,
        options: Map<String, Any?>,
        onEvent: ((String) -> Unit)?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var requestIdForCleanup: String? = null
        runCatching {
            require(text.isNotBlank()) { "TTS text is empty" }
            val normalizedBaseUrl = normalizeBaseUrl(baseUrl)
            require(normalizedBaseUrl.isNotBlank()) { "TTS Base URL is empty" }

            val requestId = UUID.randomUUID().toString().take(8)
            requestIdForCleanup = requestId
            stopInternal(reason = "superseded_by_new_request", notify = null)

            val payload = JSONObject().apply {
                put("request_id", requestId)
                put("character_name", options["character_name"] ?: "")
                put("text", text)
                put("text_lang", options["text_lang"] ?: "ja")
                put("ref_audio_path", options["ref_audio_path"] ?: "")
                put("prompt_text", options["prompt_text"] ?: "")
                put("prompt_lang", options["prompt_lang"] ?: "")
                put("text_split_method", "cut5")
                put("batch_size", 1)
                put("speed_factor", options["speed_factor"] ?: 1.0)
                put("split_sentence", true)
            }

            emitEvent(onEvent, requestId, "Request sent: chars=${text.length}")

            val request = Request.Builder()
                .url(normalizedBaseUrl.toHttpUrl().newBuilder().addPathSegment("tts").build())
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val call = httpClient.newCall(request)
            synchronized(stateLock) {
                activeCall = call
                activeRequestId = requestId
                activeBaseUrl = normalizedBaseUrl
                activeStopReason = null
            }

            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    error(
                        "TTS request failed: HTTP ${response.code} ${
                            errorBody.ifBlank { response.message }
                        }"
                    )
                }

                val resolvedRequestId = response.header("X-TTS-Request-Id") ?: requestId
                val format = response.header("X-Audio-Format")?.lowercase().orEmpty()
                val sampleRate = response.header("X-Audio-Sample-Rate")?.toIntOrNull() ?: 32000
                val channels = response.header("X-Audio-Channels")?.toIntOrNull() ?: 1
                val bitsPerSample = response.header("X-Audio-Bits-Per-Sample")?.toIntOrNull() ?: 16
                val body = response.body ?: error("TTS response body is empty")

                emitEvent(
                    onEvent,
                    resolvedRequestId,
                    "Response received: format=${format.ifBlank { "legacy" }} rate=$sampleRate channels=$channels bits=$bitsPerSample"
                )

                if (format == "pcm_s16le") {
                    streamPcmToAudioTrack(
                        requestId = resolvedRequestId,
                        inputStream = body.byteStream(),
                        sampleRate = sampleRate,
                        channels = channels,
                        bitsPerSample = bitsPerSample,
                        onEvent = onEvent
                    )
                } else {
                    emitEvent(onEvent, resolvedRequestId, "Legacy response detected; falling back to file playback")
                    saveAndPlayLegacy(body.byteStream(), resolvedRequestId, onEvent)
                }
            }
        }.onFailure { e ->
            when {
                isExpectedStop(e) -> {
                    val requestId = synchronized(stateLock) { activeRequestId } ?: "unknown"
                    emitEvent(onEvent, requestId, "Playback stopped: ${e.message ?: "cancelled"}")
                    Log.i(TAG, "TTS stopped: ${e.message}")
                }
                else -> {
                    Log.e(TAG, "TTS failed: ${e.message}", e)
                    onEvent?.invoke("TTS failed: ${e.message ?: "unknown error"}")
                }
            }
        }.also {
            synchronized(stateLock) {
                if (requestIdForCleanup != null && activeRequestId == requestIdForCleanup) {
                    activeCall = null
                    activeRequestId = null
                    activeBaseUrl = null
                    activeStopReason = null
                }
            }
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
                Log.e(TAG, "Switch model failed: ${e.message}", e)
            }
        }

    override suspend fun fetchDebugLogs(baseUrl: String, limit: Int): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = normalizeBaseUrl(baseUrl).toHttpUrl().newBuilder()
                    .addPathSegments("debug/logs")
                    .addQueryParameter("limit", limit.toString())
                    .build()
                httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("Fetch debug logs failed: HTTP ${response.code} ${response.message}")
                    }
                    val body = response.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val logs = json.optJSONArray("logs") ?: JSONArray()
                    buildList {
                        for (index in 0 until logs.length()) {
                            add(logs.optString(index))
                        }
                    }
                }
            }
        }

    override suspend fun clearDebugLogs(baseUrl: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = normalizeBaseUrl(baseUrl).toHttpUrl().newBuilder()
                    .addPathSegments("debug/logs/clear")
                    .build()
                httpClient.newCall(
                    Request.Builder()
                        .url(url)
                        .post(ByteArray(0).toRequestBody())
                        .build()
                ).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("Clear debug logs failed: HTTP ${response.code} ${response.message}")
                    }
                }
            }
        }

    override fun stop() {
        stopInternal(reason = "client_stop", notify = null)
    }

    override fun release() {
        stop()
    }

    private fun streamPcmToAudioTrack(
        requestId: String,
        inputStream: InputStream,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        onEvent: ((String) -> Unit)?
    ) {
        require(bitsPerSample == 16) { "Unsupported PCM bits per sample: $bitsPerSample" }
        require(channels == 1 || channels == 2) { "Unsupported PCM channels: $channels" }

        val channelConfig = if (channels == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBufferSize > 0) {
            "AudioTrack minBufferSize failed: $minBufferSize"
        }

        val bufferSize = maxOf(minBufferSize * 4, sampleRate * channels)
        val track = createAudioTrack(sampleRate, channelConfig, bufferSize)
        require(track.state == AudioTrack.STATE_INITIALIZED) {
            "AudioTrack init failed: state=${track.state} sampleRate=$sampleRate channels=$channels bufferSize=$bufferSize"
        }
        val startedAt = System.nanoTime()
        var totalBytes = 0L
        var chunkCount = 0
        var firstChunkAtMs: Long? = null
        var playbackStarted = false
        val buffer = ByteArray(4096)
        val frameSizeBytes = channels * (bitsPerSample / 8)
        var pendingByte: Int? = null
        var totalSamplesWritten = 0L

        synchronized(stateLock) {
            audioTrack = track
        }

        try {
            emitEvent(
                onEvent,
                requestId,
                "AudioTrack ready: sampleRate=$sampleRate channels=$channels bits=$bitsPerSample minBuffer=$minBufferSize bufferSize=$bufferSize state=${track.state}"
            )
            inputStream.use { stream ->
                while (true) {
                    throwIfStopped(requestId)
                    val read = stream.read(buffer)
                    if (read == -1) {
                        break
                    }
                    if (read == 0) {
                        continue
                    }

                    if (!playbackStarted) {
                        track.play()
                        playbackStarted = true
                        firstChunkAtMs = elapsedMs(startedAt)
                        emitEvent(
                            onEvent,
                            requestId,
                            "Playback started: first_chunk_ms=$firstChunkAtMs buffer_size=$bufferSize"
                        )
                    }

                    val normalizedChunk = normalizePcmChunk(
                        source = buffer,
                        count = read,
                        frameSizeBytes = frameSizeBytes,
                        pendingByte = pendingByte
                    )
                    pendingByte = normalizedChunk.pendingByte
                    if (normalizedChunk.sampleCount > 0) {
                        writeFully(track, normalizedChunk.samples, normalizedChunk.sampleCount)
                        totalSamplesWritten += normalizedChunk.sampleCount.toLong()
                    }
                    totalBytes += read.toLong()
                    chunkCount += 1

                    if (chunkCount == 1 || chunkCount % 50 == 0) {
                        emitEvent(
                            onEvent,
                            requestId,
                            "Stream progress: chunks=$chunkCount bytes=$totalBytes elapsed_ms=${elapsedMs(startedAt)}"
                        )
                    }
                }
            }

            pendingByte?.let {
                emitEvent(onEvent, requestId, "Stream ended with 1 dangling byte; dropping trailing partial sample")
            }
            if (playbackStarted) {
                awaitPlaybackDrain(
                    track = track,
                    totalSamplesWritten = totalSamplesWritten,
                    sampleRate = sampleRate,
                    requestId = requestId,
                    onEvent = onEvent
                )
            }
            emitEvent(
                onEvent,
                requestId,
                "Playback completed: chunks=$chunkCount bytes=$totalBytes samples=$totalSamplesWritten total_ms=${elapsedMs(startedAt)}"
            )
        } finally {
            synchronized(stateLock) {
                if (audioTrack === track) {
                    audioTrack = null
                }
            }
            track.releaseSafely()
        }
    }

    private fun saveAndPlayLegacy(
        inputStream: InputStream,
        requestId: String,
        onEvent: ((String) -> Unit)?
    ) {
        try {
            val tempFile = File(context.cacheDir, "gpt_sovits_temp.wav")
            tempFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }

            val fileSizeBytes = tempFile.length()
            emitEvent(onEvent, requestId, "Legacy audio buffered: ${fileSizeBytes / 1024} KB")
            if (fileSizeBytes <= 0L) {
                error("Generated audio file is empty")
            }

            mainHandler.post {
                try {
                    stopLocalPlaybackOnly()
                    mediaPlayer = MediaPlayer().apply {
                        setOnPreparedListener {
                            emitEvent(onEvent, requestId, "Legacy playback started")
                            start()
                        }
                        setOnCompletionListener {
                            emitEvent(onEvent, requestId, "Legacy playback completed")
                            Log.d(TAG, "Legacy playback completed")
                        }
                        setOnErrorListener { _, what, extra ->
                            emitEvent(onEvent, requestId, "Legacy MediaPlayer error: what=$what extra=$extra")
                            true
                        }
                        setDataSource(tempFile.absolutePath)
                        prepareAsync()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Legacy playback failed: ${e.message}", e)
                    emitEvent(onEvent, requestId, "Legacy playback failed: ${e.message ?: "unknown error"}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Legacy playback failed: ${e.message}", e)
            throw e
        }
    }

    private fun stopInternal(reason: String, notify: ((String) -> Unit)?) {
        val requestId: String?
        val baseUrl: String?
        val call: Call?
        val track: AudioTrack?
        synchronized(stateLock) {
            requestId = activeRequestId
            baseUrl = activeBaseUrl
            call = activeCall
            track = audioTrack
            activeStopReason = reason
            activeCall = null
            audioTrack = null
            activeRequestId = null
            activeBaseUrl = null
        }

        if (requestId != null) {
            notify?.invoke("TTS[$requestId] Stop requested: $reason")
        }
        call?.cancel()
        track?.stopSafely()
        track?.releaseSafely()
        stopLocalPlaybackOnly()

        if (requestId != null && baseUrl != null) {
            sendStopRequest(baseUrl, requestId, reason)
        }
    }

    private fun stopLocalPlaybackOnly() {
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        mediaPlayer = null
    }

    private fun sendStopRequest(baseUrl: String, requestId: String, reason: String) {
        val stopUrl = try {
            normalizeBaseUrl(baseUrl).toHttpUrl().newBuilder()
                .addPathSegment("stop")
                .addQueryParameter("request_id", requestId)
                .addQueryParameter("reason", reason)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "Stop request URL build failed: ${e.message}")
            return
        }

        Thread {
            runCatching {
                httpClient.newCall(Request.Builder().url(stopUrl).post(ByteArray(0).toRequestBody()).build())
                    .execute()
                    .use { response ->
                        Log.d(TAG, "Stop request completed for $requestId with HTTP ${response.code}")
                    }
            }.onFailure { e ->
                Log.w(TAG, "Stop request failed for $requestId: ${e.message}")
            }
        }.start()
    }

    private fun throwIfStopped(requestId: String) {
        synchronized(stateLock) {
            if (activeRequestId != requestId) {
                val reason = activeStopReason ?: "request_replaced"
                throw IOException("stream stopped: $reason")
            }
        }
    }

    private fun isExpectedStop(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return error is IOException && (
            message.startsWith("stream stopped:") ||
                message.contains("canceled", ignoreCase = true)
            )
    }

    private fun writeFully(track: AudioTrack, samples: ShortArray, count: Int) {
        var offset = 0
        var zeroWriteCount = 0
        while (offset < count) {
            val remaining = count - offset
            val written = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                track.write(samples, offset, remaining, AudioTrack.WRITE_BLOCKING)
            } else {
                @Suppress("DEPRECATION")
                track.write(samples, offset, remaining)
            }
            if (written < 0) {
                error("AudioTrack write failed: $written state=${track.state} playState=${track.playState}")
            }
            if (written == 0) {
                zeroWriteCount += 1
                if (zeroWriteCount >= 5) {
                    error(
                        "AudioTrack write stalled: 0 repeated $zeroWriteCount times state=${track.state} playState=${track.playState}"
                    )
                }
                Thread.sleep(10)
                continue
            }
            zeroWriteCount = 0
            offset += written
        }
    }

    private fun normalizePcmChunk(
        source: ByteArray,
        count: Int,
        frameSizeBytes: Int,
        pendingByte: Int?
    ): NormalizedPcmChunk {
        require(frameSizeBytes >= 2) { "Unsupported frame size: $frameSizeBytes" }

        val combined = ByteArray(count + if (pendingByte != null) 1 else 0)
        var combinedLength = 0
        if (pendingByte != null) {
            combined[combinedLength++] = pendingByte.toByte()
        }
        System.arraycopy(source, 0, combined, combinedLength, count)
        combinedLength += count

        val usableBytes = combinedLength - (combinedLength % 2)
        val nextPendingByte = if (usableBytes < combinedLength) {
            combined[combinedLength - 1].toInt() and 0xFF
        } else {
            null
        }

        val sampleCount = usableBytes / 2
        val samples = ShortArray(sampleCount)
        var sampleIndex = 0
        var byteIndex = 0
        while (byteIndex + 1 < usableBytes) {
            val low = combined[byteIndex].toInt() and 0xFF
            val high = combined[byteIndex + 1].toInt()
            samples[sampleIndex++] = ((high shl 8) or low).toShort()
            byteIndex += 2
        }

        return NormalizedPcmChunk(
            samples = samples,
            sampleCount = sampleCount,
            pendingByte = nextPendingByte
        )
    }

    private fun awaitPlaybackDrain(
        track: AudioTrack,
        totalSamplesWritten: Long,
        sampleRate: Int,
        requestId: String,
        onEvent: ((String) -> Unit)?
    ) {
        if (totalSamplesWritten <= 0L) {
            track.stopSafely()
            return
        }

        val waitStartedAt = System.nanoTime()
        val timeoutMs = maxOf(
            1500L,
            TimeUnit.SECONDS.toMillis((totalSamplesWritten / sampleRate.toLong()) + 2L)
        )
        var lastReportedPosition = -1L
        var stalledPollCount = 0

        while (elapsedMs(waitStartedAt) < timeoutMs) {
            val playbackHead = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if (playbackHead >= totalSamplesWritten) {
                emitEvent(
                    onEvent,
                    requestId,
                    "Playback drain complete: playbackHead=$playbackHead target=$totalSamplesWritten wait_ms=${elapsedMs(waitStartedAt)}"
                )
                track.stopSafely()
                return
            }

            if (playbackHead == lastReportedPosition) {
                stalledPollCount += 1
            } else {
                stalledPollCount = 0
                lastReportedPosition = playbackHead
            }

            if (stalledPollCount > 20) {
                emitEvent(
                    onEvent,
                    requestId,
                    "Playback drain stalled: playbackHead=$playbackHead target=$totalSamplesWritten wait_ms=${elapsedMs(waitStartedAt)}"
                )
                break
            }

            sleep(25)
        }

        emitEvent(
            onEvent,
            requestId,
            "Playback drain timeout: playbackHead=${track.playbackHeadPosition.toLong() and 0xFFFFFFFFL} target=$totalSamplesWritten timeout_ms=$timeoutMs"
        )
        track.stopSafely()
    }

    private fun createAudioTrack(sampleRate: Int, channelConfig: Int, bufferSize: Int): AudioTrack {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM
            )
        }
    }

    private fun elapsedMs(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private fun emitEvent(onEvent: ((String) -> Unit)?, requestId: String, message: String) {
        onEvent?.invoke("TTS[$requestId] $message")
    }

    private fun normalizeBaseUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        return when {
            trimmed.isBlank() -> trimmed
            trimmed.endsWith("/") -> trimmed
            else -> "$trimmed/"
        }
    }

    private fun AudioTrack.stopSafely() {
        runCatching {
            if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                stop()
            }
        }
    }

    private fun AudioTrack.releaseSafely() {
        runCatching { release() }
    }

    private fun createApi(baseUrl: String): GptSoVitsApi {
        return Retrofit.Builder()
            .baseUrl(normalizeBaseUrl(baseUrl))
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GptSoVitsApi::class.java)
    }

    private companion object {
        const val TAG = "GptSoVits"
    }

    private data class NormalizedPcmChunk(
        val samples: ShortArray,
        val sampleCount: Int,
        val pendingByte: Int?
    )
}
