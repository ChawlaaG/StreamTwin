package com.streamtwin.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamTwinSecurePrefs @Inject constructor(
    @ApplicationContext context: Context
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "streamtwin_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveAccessToken(token: String) {
        sharedPrefs.edit().putString("ACCESS_TOKEN", token).apply()
    }

    fun getAccessToken(): String? {
        return sharedPrefs.getString("ACCESS_TOKEN", null)
    }

    fun saveStreamKey(key: String) {
        sharedPrefs.edit().putString("STREAM_KEY", key).apply()
    }

    fun getStreamKey(): String? {
        return sharedPrefs.getString("STREAM_KEY", null)
    }

    fun clearAll() {
        sharedPrefs.edit().clear().apply()
    }
}
