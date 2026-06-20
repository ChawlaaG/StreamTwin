package com.streamtwin.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.streamtwin.data.local.SecureStorageManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject

@AndroidEntryPoint
class YouTubeChatService : Service() {

    @Inject
    lateinit var secureStorageManager: SecureStorageManager

    private val TAG = "YouTubeChatService"
    private val client = OkHttpClient()
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    
    private var liveChatId: String? = null
    private var isPolling = false
    private var nextPageToken: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "STOP_CHAT") {
            stopSelf()
            return START_NOT_STICKY
        }

        startYouTubeChat()
        return START_STICKY
    }

    private fun startYouTubeChat() {
        if (isPolling) return
        isPolling = true
        
        serviceScope.launch {
            val token = secureStorageManager.youtubeAccessToken
            if (token.isEmpty()) {
                Log.e(TAG, "No YouTube Access Token found. Cannot start chat.")
                isPolling = false
                return@launch
            }
            
            // 1. Fetch active broadcast to get liveChatId
            fetchLiveChatId(token)
            
            // 2. Poll for messages
            if (liveChatId != null) {
                pollChatMessages(token)
            } else {
                Log.e(TAG, "Could not retrieve liveChatId. Polling aborted.")
                isPolling = false
            }
        }
    }

    private suspend fun fetchLiveChatId(token: String) {
        val request = Request.Builder()
            .url("https://www.googleapis.com/youtube/v3/liveBroadcasts?part=snippet&broadcastStatus=active&broadcastType=all")
            .addHeader("Authorization", "Bearer $token")
            .build()
            
        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val jsonStr = response.body?.string() ?: return
                val jsonObject = JSONObject(jsonStr)
                val items = jsonObject.optJSONArray("items")
                if (items != null && items.length() > 0) {
                    val broadcast = items.getJSONObject(0)
                    val snippet = broadcast.getJSONObject("snippet")
                    liveChatId = snippet.optString("liveChatId")
                    Log.d(TAG, "Found active broadcast with liveChatId: $liveChatId")
                } else {
                    Log.e(TAG, "No active broadcasts found.")
                }
            } else {
                Log.e(TAG, "Failed to fetch broadcasts: ${response.code} ${response.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching live broadcasts", e)
        }
    }

    private suspend fun pollChatMessages(token: String) {
        val chatId = liveChatId ?: return
        
        while (serviceScope.isActive && isPolling) {
            var url = "https://www.googleapis.com/youtube/v3/liveChat/messages?liveChatId=$chatId&part=snippet,authorDetails"
            if (nextPageToken != null) {
                url += "&pageToken=$nextPageToken"
            }
            
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .build()
                
            var pollingInterval = 5000L // Default fallback
                
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val jsonStr = response.body?.string() ?: continue
                    val jsonObject = JSONObject(jsonStr)
                    
                    // Recommended interval provided by API
                    pollingInterval = jsonObject.optLong("pollingIntervalMillis", 5000L)
                    nextPageToken = jsonObject.optString("nextPageToken", null)
                    
                    val items = jsonObject.optJSONArray("items")
                    if (items != null) {
                        for (i in 0 until items.length()) {
                            val msgItem = items.getJSONObject(i)
                            val snippet = msgItem.optJSONObject("snippet")
                            val authorDetails = msgItem.optJSONObject("authorDetails")
                            
                            val messageText = snippet?.optString("displayMessage", "") ?: ""
                            val authorName = authorDetails?.optString("displayName", "User") ?: "User"
                            
                            // Let's filter out early startup items and system messages or empty messages
                            if (messageText.isNotEmpty()) {
                                Log.d(TAG, "YT Chat: $authorName: $messageText")
                                ChatStateManager.addMessage(authorName, messageText, "YOUTUBE")
                            }
                        }
                    }
                } else {
                    Log.e(TAG, "Error fetching chat messages: ${response.code} ${response.message}")
                    pollingInterval = 10000L // Back off on error
                    if (response.code == 401) {
                        Log.e(TAG, "Auth token expired or invalid.")
                        isPolling = false
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception polling chat", e)
                pollingInterval = 10000L // Back off
            }
            
            // Wait before next request
            delay(pollingInterval)
        }
    }

    override fun onDestroy() {
        isPolling = false
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
