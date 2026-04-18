package com.moe.tsunderetranslator.data.remote

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Streaming
interface GptSoVitsApi {
    @POST("tts")
    @Streaming
    suspend fun generateTts(@Body params: Map<String, @JvmSuppressWildcards Any>): Response<ResponseBody>

    @GET("set_gpt_weights")
    suspend fun setGptWeights(@Query("weights_path") path: String): Response<Unit>

    @GET("set_sovits_weights")
    suspend fun setSovitsWeights(@Query("weights_path") path: String): Response<Unit>

    @GET("/")
    suspend fun checkStatus(): Response<Unit>
}