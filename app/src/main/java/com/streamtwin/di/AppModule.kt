package com.streamtwin.di

import com.streamtwin.data.remote.TwitchApiService
import com.streamtwin.data.repository.TwitchRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    // TwitchRepository, StreamTwinSecurePrefs, and StreamDataStore are provided 
    // via @Inject constructor and @Singleton on their respective classes.
}
