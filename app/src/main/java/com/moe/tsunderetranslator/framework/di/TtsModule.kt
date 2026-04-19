package com.moe.tsunderetranslator.framework.di

import android.content.Context
import com.moe.tsunderetranslator.domain.provider.TtsProvider
import com.moe.tsunderetranslator.framework.tts.GptSoVitsRemoteImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TtsModule {

    @Provides
    @Singleton
    fun provideTtsProvider(
        @ApplicationContext context: Context
    ): TtsProvider {
        return GptSoVitsRemoteImpl(context)
    }
}
