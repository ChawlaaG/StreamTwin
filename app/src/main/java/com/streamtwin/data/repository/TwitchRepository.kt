package com.streamtwin.data.repository

import com.streamtwin.data.local.StreamTwinSecurePrefs
import com.streamtwin.data.remote.TwitchApiService
import com.streamtwin.data.remote.model.TwitchCategory
import com.streamtwin.domain.model.TwitchUser
import com.streamtwin.util.AppConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class TwitchRepository @Inject constructor(
    private val apiService: TwitchApiService,
    private val securePreferences: StreamTwinSecurePrefs
) {

    suspend fun getCurrentUser(): Result<TwitchUser> = withContext(Dispatchers.IO) {
        try {
            val token = securePreferences.getAccessToken() ?: throw Exception("No access token found")
            val authHeader = "Bearer $token"
            val response = apiService.getUsers(authHeader, AppConfig.TWITCH_CLIENT_ID)
            
            val userData = response.data.firstOrNull() ?: throw Exception("User data empty")
            
            Result.success(
                TwitchUser(
                    id = userData.id,
                    login = userData.login,
                    displayName = userData.displayName,
                    profileImageUrl = userData.profileImageUrl
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchAndSaveStreamKey(userId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val token = securePreferences.getAccessToken() ?: throw Exception("No access token found")
            val authHeader = "Bearer $token"
            val response = apiService.getStreamKey(authHeader, AppConfig.TWITCH_CLIENT_ID, userId)
            
            val key = response.data.firstOrNull()?.streamKey ?: throw Exception("Stream key empty")
            securePreferences.saveStreamKey(key)
            Result.success(key)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun saveAccessToken(token: String) {
        securePreferences.saveAccessToken(token)
    }

    fun getStreamKey(): String? {
        return securePreferences.getStreamKey()
    }

    fun hasToken(): Boolean {
        return securePreferences.getAccessToken() != null
    }

    suspend fun updateStreamInfo(userId: String, title: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val token = securePreferences.getAccessToken() ?: throw Exception("No access token found")
            val authHeader = "Bearer $token"
            val body = mutableMapOf<String, String>()
            body["title"] = title
            // Note: game_id is optional but passed here if provided
            
            val response = apiService.modifyChannelInformation(authHeader, AppConfig.TWITCH_CLIENT_ID, userId, body)
            
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                throw Exception("Failed to update stream info: ${response.code()}")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateStreamMetadata(userId: String, title: String?, gameId: String?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val token = securePreferences.getAccessToken() ?: throw Exception("No access token found")
            val authHeader = "Bearer $token"
            val body = mutableMapOf<String, String>()
            if (title != null) body["title"] = title
            if (gameId != null) body["game_id"] = gameId
            
            val response = apiService.modifyChannelInformation(authHeader, AppConfig.TWITCH_CLIENT_ID, userId, body)
            if (response.isSuccessful) Result.success(Unit)
            else throw Exception("Update failed: ${response.code()}")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun searchCategories(query: String): Result<List<TwitchCategory>> = withContext(Dispatchers.IO) {
        try {
            val token = securePreferences.getAccessToken() ?: throw Exception("No access token found")
            val authHeader = "Bearer $token"
            val response = apiService.searchCategories(authHeader, AppConfig.TWITCH_CLIENT_ID, query)
            Result.success(response.data)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun logout() {
        securePreferences.clearAll()
    }
}
