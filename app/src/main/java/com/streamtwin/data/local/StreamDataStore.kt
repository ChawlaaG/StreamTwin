package com.streamtwin.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
    private val SELECTED_GAME_ID_KEY = stringPreferencesKey("selected_game_id")
    private val SELECTED_GAME_NAME_KEY = stringPreferencesKey("selected_game_name")
    private val MIC_VOLUME_KEY = intPreferencesKey("mic_volume")
    private val INTERNAL_VOLUME_KEY = intPreferencesKey("internal_volume")
    private val KICK_ENABLED_KEY = booleanPreferencesKey("kick_enabled")
    private val YOUTUBE_ENABLED_KEY = booleanPreferencesKey("youtube_enabled")
    private val TWITCH_ENABLED_KEY = booleanPreferencesKey("twitch_enabled")
    private val ASPECT_RATIO_KEY = stringPreferencesKey("aspect_ratio") // "landscape" or "portrait"

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

    private val STREAM_SAVE_VOD_KEY = booleanPreferencesKey("stream_save_vod")

    val saveVodLocallyFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[STREAM_SAVE_VOD_KEY] ?: false
    }

    suspend fun saveVodLocally(save: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[STREAM_SAVE_VOD_KEY] = save
        }
    }

    val selectedGameIdFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[SELECTED_GAME_ID_KEY] ?: "509658" // Default to Just Chatting
    }

    val selectedGameNameFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[SELECTED_GAME_NAME_KEY] ?: "Just Chatting"
    }

    suspend fun saveSelectedGame(id: String, name: String) {
        context.dataStore.edit { preferences ->
            preferences[SELECTED_GAME_ID_KEY] = id
            preferences[SELECTED_GAME_NAME_KEY] = name
        }
    }

    val micVolumeFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[MIC_VOLUME_KEY] ?: 100
    }

    suspend fun saveMicVolume(volume: Int) {
        context.dataStore.edit { preferences ->
            preferences[MIC_VOLUME_KEY] = volume
        }
    }

    val internalVolumeFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[INTERNAL_VOLUME_KEY] ?: 100
    }

    suspend fun saveInternalVolume(volume: Int) {
        context.dataStore.edit { preferences ->
            preferences[INTERNAL_VOLUME_KEY] = volume
        }
    }

    val kickEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KICK_ENABLED_KEY] ?: false
    }

    suspend fun saveKickEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KICK_ENABLED_KEY] = enabled
        }
    }

    val youtubeEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[YOUTUBE_ENABLED_KEY] ?: false
    }

    suspend fun saveYoutubeEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[YOUTUBE_ENABLED_KEY] = enabled
        }
    }

    val twitchEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[TWITCH_ENABLED_KEY] ?: false
    }

    suspend fun saveTwitchEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[TWITCH_ENABLED_KEY] = enabled
        }
    }

    val aspectRatioFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[ASPECT_RATIO_KEY] ?: "landscape"
    }

    suspend fun saveAspectRatio(ratio: String) {
        context.dataStore.edit { preferences ->
            preferences[ASPECT_RATIO_KEY] = ratio
        }
    }

    private val CLIP_DURATION_KEY = intPreferencesKey("clip_duration")
    private val CLIP_INCLUDE_MIC_KEY = booleanPreferencesKey("clip_include_mic")
    private val CLIP_ORIENTATION_KEY = stringPreferencesKey("clip_orientation")

    val clipDurationFlow: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[CLIP_DURATION_KEY] ?: 60
    }

    suspend fun saveClipDuration(seconds: Int) {
        context.dataStore.edit { preferences ->
            preferences[CLIP_DURATION_KEY] = seconds
        }
    }

    val clipIncludeMicFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[CLIP_INCLUDE_MIC_KEY] ?: true
    }

    suspend fun saveClipIncludeMic(include: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[CLIP_INCLUDE_MIC_KEY] = include
        }
    }

    val clipOrientationFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[CLIP_ORIENTATION_KEY] ?: ""
    }

    suspend fun saveClipOrientation(orientation: String) {
        context.dataStore.edit { preferences ->
            preferences[CLIP_ORIENTATION_KEY] = orientation
        }
    }

    private val HAS_SKIPPED_LOGIN_KEY = booleanPreferencesKey("has_skipped_login")

    val hasSkippedLoginFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[HAS_SKIPPED_LOGIN_KEY] ?: false
    }

    suspend fun saveHasSkippedLogin(skipped: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[HAS_SKIPPED_LOGIN_KEY] = skipped
        }
    }

    // ── Back-tap clip trigger ─────────────────────────────────────────────────
    // When enabled, a triple-tap on the back of the phone fires saveClip().
    // Implemented via accelerometer, so works on all Android devices with no
    // extra permissions. Disabled by default since game haptics can cause
    // false triggers on some phones.
    private val BACK_TAP_ENABLED_KEY = booleanPreferencesKey("back_tap_enabled")

    val backTapEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[BACK_TAP_ENABLED_KEY] ?: false
    }

    suspend fun saveBackTapEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[BACK_TAP_ENABLED_KEY] = enabled
        }
    }

    private val AUTO_CLIP_ENABLED_KEY = booleanPreferencesKey("auto_clip_enabled")
    private val AUTO_CLIP_PACKAGES_KEY = stringSetPreferencesKey("auto_clip_packages")

    val autoClipEnabledFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AUTO_CLIP_ENABLED_KEY] ?: false
    }

    suspend fun saveAutoClipEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_CLIP_ENABLED_KEY] = enabled
        }
    }

    val autoClipPackagesFlow: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[AUTO_CLIP_PACKAGES_KEY] ?: emptySet()
    }

    suspend fun saveAutoClipPackages(packages: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_CLIP_PACKAGES_KEY] = packages
        }
    }
}
