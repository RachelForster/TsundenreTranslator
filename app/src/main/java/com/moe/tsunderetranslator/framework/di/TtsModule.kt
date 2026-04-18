package com.moe.tsunderetranslator.framework.di

import android.content.Context
import com.moe.tsunderetranslator.data.remote.GptSoVitsApi
import com.moe.tsunderetranslator.domain.provider.TtsProvider
import com.moe.tsunderetranslator.framework.tts.GptSoVitsRemoteImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TtsModule {

    @Provides
    @Singleton
    fun provideGptSoVitsApi(): GptSoVitsApi {
        // 这里规定了 API 的基础网址
        // 建议：实际开发中，这个 IP 可以从 SharedPreferences 或设置页面动态获取
        val pcIp = "192.168.1.100"
        val baseUrl = "http://$pcIp:9880/"

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GptSoVitsApi::class.java)
    }

    @Provides
    @Singleton
    fun provideTtsProvider(
        api: GptSoVitsApi,
        @ApplicationContext context: Context
    ): TtsProvider {
        // 将 API 注入到具体的实现类 GptSoVitsRemoteImpl 中
        return GptSoVitsRemoteImpl(api, context)
    }
}