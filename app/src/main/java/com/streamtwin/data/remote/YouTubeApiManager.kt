package com.streamtwin.data.remote

import android.util.Log
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
    private val TAG = "YouTubeApiManager"

    /**
     * Fetches the user's default stream key and RTMP URL.
     * If no live stream exists, automatically creates one.
     * Returns a Pair(StreamId, Pair(RtmpUrl, StreamKey))
     */
    suspend fun fetchStreamKey(token: String): Pair<String, Pair<String, String>>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://www.googleapis.com/youtube/v3/liveStreams?part=snippet,cdn&mine=true")
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                Log.e(TAG, "liveStreams.list failed with HTTP ${response.code}: $responseBody")
                return@withContext null
            }

            try {
                val json = JSONObject(responseBody)
                val items = json.optJSONArray("items")
                if (items != null && items.length() > 0) {
                    for (i in 0 until items.length()) {
                        val stream = items.optJSONObject(i) ?: continue
                        val streamId = stream.optString("id")
                        val cdn = stream.optJSONObject("cdn")
                        val ingestionInfo = cdn?.optJSONObject("ingestionInfo")
                        val streamName = ingestionInfo?.optString("streamName")
                        val rtmpUrl = ingestionInfo?.optString("ingestionAddress")
                        if (!streamId.isNullOrEmpty() && !streamName.isNullOrEmpty() && !rtmpUrl.isNullOrEmpty()) {
                            Log.d(TAG, "Found active YouTube live stream: id=$streamId, rtmpUrl=$rtmpUrl")
                            return@withContext Pair(streamId, Pair(rtmpUrl, streamName))
                        }
                    }
                }
                Log.w(TAG, "No usable live stream found in list. Attempting to create a new live stream...")
                return@withContext createLiveStream(token)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse YouTube liveStreams response: $responseBody", e)
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during fetchStreamKey", e)
            null
        }
    }

    /**
     * Creates a new liveStream resource when the user has no pre-existing stream key.
     */
    suspend fun createLiveStream(token: String): Pair<String, Pair<String, String>>? = withContext(Dispatchers.IO) {
        try {
            val bodyJson = JSONObject().apply {
                put("snippet", JSONObject().apply {
                    put("title", "StreamTwin Live Stream")
                    put("description", "Created automatically by StreamTwin")
                })
                put("cdn", JSONObject().apply {
                    put("frameRate", "variable")
                    put("ingestionType", "rtmp")
                    put("resolution", "variable")
                })
            }

            val request = Request.Builder()
                .url("https://www.googleapis.com/youtube/v3/liveStreams?part=snippet,cdn")
                .addHeader("Authorization", "Bearer $token")
                .post(bodyJson.toString().toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                Log.e(TAG, "liveStreams.insert failed with HTTP ${response.code}: $responseBody")
                return@withContext null
            }

            val json = JSONObject(responseBody)
            val streamId = json.optString("id")
            val cdn = json.optJSONObject("cdn")
            val ingestionInfo = cdn?.optJSONObject("ingestionInfo")
            val streamName = ingestionInfo?.optString("streamName")
            val rtmpUrl = ingestionInfo?.optString("ingestionAddress")

            if (!streamId.isNullOrEmpty() && !streamName.isNullOrEmpty() && !rtmpUrl.isNullOrEmpty()) {
                Log.d(TAG, "Successfully created new live stream: id=$streamId, rtmpUrl=$rtmpUrl")
                return@withContext Pair(streamId, Pair(rtmpUrl, streamName))
            }

            Log.e(TAG, "Created liveStream lacked ingestionInfo: $responseBody")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Exception during createLiveStream", e)
            null
        }
    }

    /**
     * Creates a new live broadcast and binds it to the provided streamId.
     * Falls back to unlisted privacy if public broadcast is disallowed for unverified apps.
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

            var request = Request.Builder()
                .url("https://www.googleapis.com/youtube/v3/liveBroadcasts?part=snippet,status,contentDetails")
                .addHeader("Authorization", "Bearer $token")
                .post(bodyJson.toString().toRequestBody(jsonMediaType))
                .build()

            var response = client.newCall(request).execute()
            var responseBody = response.body?.string() ?: ""

            // Fallback to unlisted privacy if public is restricted
            if (!response.isSuccessful && (response.code == 400 || response.code == 403)) {
                Log.w(TAG, "Public broadcast creation failed (HTTP ${response.code}). Retrying with privacyStatus='unlisted': $responseBody")
                bodyJson.getJSONObject("status").put("privacyStatus", "unlisted")
                request = Request.Builder()
                    .url("https://www.googleapis.com/youtube/v3/liveBroadcasts?part=snippet,status,contentDetails")
                    .addHeader("Authorization", "Bearer $token")
                    .post(bodyJson.toString().toRequestBody(jsonMediaType))
                    .build()
                response = client.newCall(request).execute()
                responseBody = response.body?.string() ?: ""
            }

            if (!response.isSuccessful) {
                Log.e(TAG, "liveBroadcasts.insert failed with HTTP ${response.code}: $responseBody")
                return@withContext null
            }
            
            val broadcastId = JSONObject(responseBody).optString("id")
            if (broadcastId.isEmpty()) {
                Log.e(TAG, "Broadcast response missing id: $responseBody")
                return@withContext null
            }

            // 2. Bind Broadcast to Stream
            val bindRequest = Request.Builder()
                .url("https://www.googleapis.com/youtube/v3/liveBroadcasts/bind?id=$broadcastId&streamId=$streamId&part=id,snippet")
                .addHeader("Authorization", "Bearer $token")
                .post("".toRequestBody(jsonMediaType))
                .build()

            val bindResponse = client.newCall(bindRequest).execute()
            val bindResponseBody = bindResponse.body?.string() ?: ""
            if (bindResponse.isSuccessful) {
                Log.d(TAG, "Successfully bound broadcast $broadcastId to stream $streamId")
                return@withContext broadcastId
            } else {
                Log.e(TAG, "liveBroadcasts.bind failed with HTTP ${bindResponse.code}: $bindResponseBody")
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Exception during createAndBindBroadcast", e)
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
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                Log.e(TAG, "transitionToLive failed with HTTP ${response.code}: $responseBody")
            }
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "Exception during transitionToLive", e)
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
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                Log.e(TAG, "transitionToComplete failed with HTTP ${response.code}: $responseBody")
            }
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "Exception during transitionToComplete", e)
            false
        }
    }
}
