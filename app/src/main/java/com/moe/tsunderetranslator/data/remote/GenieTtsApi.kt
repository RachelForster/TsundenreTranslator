package com.moe.tsunderetranslator.data.remote

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import retrofit2.http.Streaming

interface GenieTtsApi {
    /**
     * 1. 加载角色模型
     */
    @FormUrlEncoded
    @POST("load_character")
    suspend fun loadCharacter(
        @Field("character_name") characterName: String,
        @Field("onnx_model_dir") modelDir: String,
        @Field("language") language: String
    ): Response<ResponseBody>

    /**
     * 2. 设置参考音频
     */
    @FormUrlEncoded
    @POST("set_reference_audio")
    suspend fun setReferenceAudio(
        @Field("character_name") characterName: String,
        @Field("audio_path") audioPath: String,
        @Field("audio_text") audioText: String,
        @Field("language") language: String
    ): Response<ResponseBody>

    /**
     * 3. 文本转语音 (TTS)
     */
    @FormUrlEncoded
    @POST("tts")
    @Streaming
    suspend fun generateTts(
        @Field("character_name") characterName: String,
        @Field("text") text: String,
        @Field("split_sentence") splitSentence: Boolean = false,
        @Field("save_path") savePath: String? = null
    ): Response<ResponseBody>

    /**
     * 4. 卸载角色模型
     */
    @FormUrlEncoded
    @POST("unload_character")
    suspend fun unloadCharacter(
        @Field("character_name") characterName: String
    ): Response<ResponseBody>

    @POST("stop")
    suspend fun stopAllTasks(): Response<ResponseBody>

    @POST("clear_reference_audio_cache")
    suspend fun clearCache(): Response<ResponseBody>
}