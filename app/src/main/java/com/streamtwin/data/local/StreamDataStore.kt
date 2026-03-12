package com.streamtwin.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "stream_settings")

@Singleton
class StreamDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val STREAM_TITLE_KEY = stringPreferencesKey("stream_title")
    private val STREAM_Q_KEY = stringPreferencesKey("stream_quality") // e.g. "720p"
    private val STREAM_FPS_KEY = intPreferencesKey("stream_fps")
    private val STREAM_BITRATE_KEY = intPreferencesKey("stream_bitrate")

    val streamTitleFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[STREAM_TITLE_KEY] ?: "My Awesome Stream"
    }

    suspend fun saveStreamTitle(title: String) {
        context.dataStore.edit { preferences ->
            preferences[STREAM_TITLE_KEY] = title
        }
    }

    val streamQualityFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[STREAM_Q_KEY] ?: "720p"
    }

    suspend fun saveStreamQuality(quality: String) {
        context.dataStore.edit { preferences ->
            preferences[STREAM_Q_KEY] = quality
        }
    }

    val streamFpsFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[STREAM_FPS_KEY] ?: 60
    }

    suspend fun saveStreamFps(fps: Int) {
        context.dataStore.edit { preferences ->
            preferences[STREAM_FPS_KEY] = fps
        }
    }

    val streamBitrateFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[STREAM_BITRATE_KEY] ?: (6000 * 1024)
    }

    suspend fun saveStreamBitrate(bitrate: Int) {
        context.dataStore.edit { preferences ->
            preferences[STREAM_BITRATE_KEY] = bitrate
        }
    }

    private val STREAM_SAVE_VOD_KEY = androidx.datastore.preferences.core.booleanPreferencesKey("stream_save_vod")

    val saveVodLocallyFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[STREAM_SAVE_VOD_KEY] ?: false
    }

    suspend fun saveVodLocally(save: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[STREAM_SAVE_VOD_KEY] = save
        }
    }
}
