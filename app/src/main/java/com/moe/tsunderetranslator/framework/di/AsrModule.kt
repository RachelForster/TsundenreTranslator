package com.moe.tsunderetranslator.framework.di

import com.moe.tsunderetranslator.domain.provider.AsrProvider
import com.moe.tsunderetranslator.framework.asr.MoonshineVoiceAsrProviderImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AsrModule {

    @Binds
    @Singleton
    abstract fun bindAsrProvider(
        moonshineVoiceAsrProviderImpl: MoonshineVoiceAsrProviderImpl
    ): AsrProvider
}