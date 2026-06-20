package com.streamtwin.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureStorageManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secure_stream_settings",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val KICK_STREAM_KEY = "kick_stream_key"
        private const val KICK_RTMP_URL = "kick_rtmp_url"
        private const val YOUTUBE_STREAM_KEY = "youtube_stream_key"
        private const val DEFAULT_KICK_URL = "rtmp://fa723fc1b171.global-contribute.live-video.net/app/"
    }

    var kickStreamKey: String
        get() = sharedPreferences.getString(KICK_STREAM_KEY, "") ?: ""
        set(value) = sharedPreferences.edit().putString(KICK_STREAM_KEY, value).apply()

    var kickRtmpUrl: String
        get() = sharedPreferences.getString(KICK_RTMP_URL, DEFAULT_KICK_URL) ?: DEFAULT_KICK_URL
        set(value) = sharedPreferences.edit().putString(KICK_RTMP_URL, value).apply()

    var youtubeStreamKey: String
        get() = sharedPreferences.getString(YOUTUBE_STREAM_KEY, "") ?: ""
        set(value) = sharedPreferences.edit().putString(YOUTUBE_STREAM_KEY, value).apply()

    var youtubeAccessToken: String
        get() = sharedPreferences.getString("youtube_access_token", "") ?: ""
        set(value) = sharedPreferences.edit().putString("youtube_access_token", value).apply()
}
