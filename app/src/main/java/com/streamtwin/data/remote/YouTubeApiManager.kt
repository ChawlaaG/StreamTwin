package com.streamtwin.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

@Singleton
class YouTubeApiManager @Inject constructor() {
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json".toMediaTypeOrNull()

    /**
     * Fetches the user's default stream key and RTMP URL.
     * Returns a Pair(StreamId, Pair(RtmpUrl, StreamKey))
     */
    suspend fun fetchStreamKey(token: String): Pair<String, Pair<String, String>>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://www.googleapis.com/youtube/v3/liveStreams?part=snippet,cdn&mine=true")
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val responseBody = response.body?.string() ?: return@withContext null

            try {
                val json = JSONObject(responseBody)
                val items = json.optJSONArray("items")
                if (items != null && items.length() > 0) {
                    // Return the first active stream key
                    val stream = items.getJSONObject(0)
                    val streamId = stream.getString("id")
                    val cdn = stream.getJSONObject("cdn")
                    val ingestionInfo = cdn.getJSONObject("ingestionInfo")
                    val streamName = ingestionInfo.getString("streamName") // This is the stream key
                    val rtmpUrl = ingestionInfo.getString("ingestionAddress")
                    return@withContext Pair(streamId, Pair(rtmpUrl, streamName))
                }
                android.util.Log.e("YouTubeApiManager", "No items found in response: $responseBody")
                return@withContext null
            } catch (e: Exception) {
                android.util.Log.e("YouTubeApiManager", "Failed to parse YouTube API response.\nResponse body: $responseBody", e)
                return@withContext null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Creates a new live broadcast and binds it to the provided streamId.
     * Returns the broadcast ID.
     */
    suspend fun createAndBindBroadcast(token: String, title: String, description: String, streamId: String): String? = withContext(Dispatchers.IO) {
        try {
            // 1. Create Broadcast
            val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
            df.timeZone = TimeZone.getTimeZone("UTC")
            val startTime = df.format(Date(System.currentTimeMillis() + 5000)) // Start in 5 seconds

            val bodyJson = JSONObject().apply {
                put("snippet", JSONObject().apply {
                    put("title", title)
                    put("description", description)
                    put("scheduledStartTime", startTime)
                })
                put("status", JSONObject().apply {
                    put("privacyStatus", "public")
                })
                put("contentDetails", JSONObject().apply {
                    put("enableAutoStart", true)
                    put("enableAutoStop", true)
                    put("latencyPreference", "ultraLow")
                })
            }

            val request = Request.Builder()
                .url("https://www.googleapis.com/youtube/v3/liveBroadcasts?part=snippet,status,contentDetails")
                .addHeader("Authorization", "Bearer $token")
                .post(bodyJson.toString().toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val responseBody = response.body?.string() ?: return@withContext null
            
            val broadcastId = JSONObject(responseBody).getString("id")

            // 2. Bind Broadcast to Stream
            val bindRequest = Request.Builder()
                .url("https://www.googleapis.com/youtube/v3/liveBroadcasts/bind?id=$broadcastId&streamId=$streamId&part=id,snippet")
                .addHeader("Authorization", "Bearer $token")
                .post("".toRequestBody(jsonMediaType))
                .build()

            val bindResponse = client.newCall(bindRequest).execute()
            if (bindResponse.isSuccessful) {
                return@withContext broadcastId
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Transitions a bound broadcast to 'live' status.
     * Use this after RTMP connection succeeds.
     */
    suspend fun transitionToLive(token: String, broadcastId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://www.googleapis.com/youtube/v3/liveBroadcasts/transition?broadcastStatus=live&id=$broadcastId&part=id,status")
                .addHeader("Authorization", "Bearer $token")
                .post("".toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Transitions a live broadcast to 'complete' status.
     * Use this when stopping the stream from the overlay.
     */
    suspend fun transitionToComplete(token: String, broadcastId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://www.googleapis.com/youtube/v3/liveBroadcasts/transition?broadcastStatus=complete&id=$broadcastId&part=id,status")
                .addHeader("Authorization", "Bearer $token")
                .post("".toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
