package com.moe.tsunderetranslator.data.repository

import android.content.SharedPreferences
import com.moe.tsunderetranslator.domain.model.ChatMessage
import com.moe.tsunderetranslator.domain.model.ChatRole
import com.moe.tsunderetranslator.domain.model.LlmSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.content.edit

@Singleton
class ChatRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val sharedPreferences: SharedPreferences
) {
    suspend fun streamChatCompletion(
        settings: LlmSettings,
        messages: List<ChatMessage>,
        onChunk: suspend (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val normalizedBaseUrl = settings.baseUrl.trim().trimEnd('/')
        val requestBody = JSONObject().apply {
            put("model", settings.model.trim())
            put("stream", true)
            put("messages", JSONArray().apply {
                messages.forEach { message ->
                    put(
                        JSONObject().apply {
                            put("role", message.role.apiValue)
                            put("content", message.content)
                        }
                    )
                }
            })
        }

        val request = Request.Builder()
            .url("$normalizedBaseUrl/chat/completions")
            .addHeader("Authorization", "Bearer ${settings.apiKey.trim()}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                throw IOException("HTTP ${response.code}: ${errorBody.ifBlank { response.message }}")
            }

            val reader = response.body?.charStream()?.buffered()
                ?: throw IOException("Empty response body")

            while (true) {
                val rawLine = reader.readLine() ?: break
                val line = rawLine.trim()
                if (!line.startsWith("data:")) continue

                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") continue

                val json = JSONObject(payload)
                val choices = json.optJSONArray("choices") ?: continue
                if (choices.length() == 0) continue

                val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: continue
                val content = delta.optString("content")
                if (content.isNotEmpty()) {
                    onChunk(content)
                }
            }
        }
    }

    fun loadMessages(): List<ChatMessage> {
        val raw = sharedPreferences.getString(KEY_MESSAGES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val role = item.optString("role").toChatRole() ?: continue
                    add(
                        ChatMessage(
                            id = item.optString("id"),
                            role = role,
                            content = item.optString("content")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveMessages(messages: List<ChatMessage>) {
        val serialized = JSONArray().apply {
            messages.forEach { message ->
                put(
                    JSONObject().apply {
                        put("id", message.id)
                        put("role", message.role.apiValue)
                        put("content", message.content)
                    }
                )
            }
        }
        sharedPreferences.edit { putString(KEY_MESSAGES, serialized.toString()) }
    }

    private fun String.toChatRole(): ChatRole? {
        return ChatRole.entries.firstOrNull { it.apiValue == this }
    }

    private companion object {
        const val KEY_MESSAGES = "chat.messages"
    }
}
