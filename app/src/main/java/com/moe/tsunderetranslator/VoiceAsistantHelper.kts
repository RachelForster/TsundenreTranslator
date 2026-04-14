import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.*

class VoiceAssistantHelper(
    private val context: Context,
    private val apiKey: String,
    private val onStatusUpdate: (String) -> Unit // 用于 UI 显示状态
) : TextToSpeech.OnInitListener {

    private val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
    private var tts: TextToSpeech = TextToSpeech(context, this)
    private val client = OkHttpClient()

    // 1. 启动语音识别
    fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toString())
        }

        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0) ?: ""
                onStatusUpdate("识别到: $text")
                callLLM(text) // 识别成功后直接调 LLM
            }
            override fun onReadyForSpeech(params: Bundle?) = onStatusUpdate("请说话...")
            override fun onError(error: Int) = onStatusUpdate("识别错误: $error")
            // 其他回调可按需空实现
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer.startListening(intent)
    }

    // 2. 调用 LLM (OpenAI 格式)
    private fun callLLM(userInput: String) {
        onStatusUpdate("思考中...")

        val url = "https://api.openai.com/v1/chat/completions" // 也可以换成 DeepSeek 或其他国内中转
        val json = JSONObject().apply {
            put("model", "gpt-3.5-turbo")
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", userInput)
            }))
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onStatusUpdate("网络错误: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                val aiText = JSONObject(body)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")

                onStatusUpdate("AI: $aiText")
                speak(aiText) // LLM 响应后直接 TTS
            }
        })
    }

    // 3. TTS 语音输出
    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.CHINESE
    }

    fun onDestroy() {
        speechRecognizer.destroy()
        tts.shutdown()
    }
}