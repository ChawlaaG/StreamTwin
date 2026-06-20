package com.streamtwin.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import android.os.PowerManager
import com.pedro.common.ConnectChecker
import com.pedro.rtmp.rtmp.RtmpClient
import com.streamtwin.MainActivity
import com.streamtwin.R
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Environment
import com.streamtwin.util.AppConfig
import com.streamtwin.data.local.StreamDataStore
import com.streamtwin.data.local.SecureStorageManager
import androidx.core.content.ContextCompat
import android.provider.Settings
import com.streamtwin.data.remote.YouTubeApiManager
import com.streamtwin.data.repository.TwitchRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class StreamingService : Service() {

    @Inject lateinit var repository: TwitchRepository
    @Inject lateinit var secureStorageManager: SecureStorageManager
    @Inject lateinit var streamDataStore: StreamDataStore
    @Inject lateinit var youtubeApiManager: YouTubeApiManager

    private val TAG = "StreamingService"
    private val CHANNEL_ID = "StreamTwinChannel"

    // ── Single encoder / display instance (Master = Twitch) ─────────
    private var masterDisplay: MultiRtmpDisplay? = null
    private var intentData: Pair<Int, Intent>? = null

    // ── Retry state per platform ────────────────────────────────────
    private var twitchRetryCount = 0
    private var youtubeRetryCount = 0
    private val MAX_RETRIES = 3

    // ── YouTube URL & Broadcast ID ──────────────────────────────────
    private var youtubeRtmpUrl: String = ""
    private var youtubeStreamKeyDynamic: String = ""
    private var youtubeBroadcastId: String? = null

    private var serviceScope: CoroutineScope? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentStreamKey: String = ""
    private var shouldSaveVod = false
    private var clipManager: ClipManager? = null

    // ── Dynamic Master/Secondary logic ──
    private var primaryPlatform: String = "TWITCH"
    private val secondaryPlatforms = mutableListOf<String>()

    // Hold the established MediaProjection so the intent token does not expire on Android 14+
    private var activeMediaProjection: MediaProjection? = null

    // ── Lifecycle ───────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        serviceScope = CoroutineScope(Dispatchers.Default + Job())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        // ── CRITICAL GUARD: Reject ALL streaming commands while Clip Mode is active ──
        if (ClipModeService.isRunning && intent.action != "STOP_STREAM") {
            Log.w(TAG, "Ignoring intent '${intent.action}' because ClipModeService is active.")
            stopSelf()
            return START_NOT_STICKY
        }

        // ── Handle actions that do NOT need foreground or already have it ──
        when (intent.action) {
            "STOP_STREAM" -> {
                stopStreaming()
                return START_NOT_STICKY
            }
            "START_STREAM_NOW" -> {
                if (ClipModeService.isRunning) {
                    Toast.makeText(this, "Stop Clip Mode before starting stream", Toast.LENGTH_SHORT).show()
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (intentData == null) {
                    // No MediaProjection data — cannot start streaming.
                    // This happens when the overlay accidentally triggers a stream start
                    // without a MediaProjection grant (e.g. after process death, or in
                    // clip-only mode).
                    Log.w(TAG, "START_STREAM_NOW: No MediaProjection data. Cannot start stream.")
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(applicationContext, "Please start stream from the app.", Toast.LENGTH_SHORT).show()
                    }
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (masterDisplay != null) {
                    Log.d(TAG, "START_STREAM_NOW ignored: Stream is already active or reconnecting.")
                    return START_NOT_STICKY
                }
                // Service is already foreground from the initial start; proceed directly.
                Log.d(TAG, "START_STREAM_NOW: Starting destinations.")
                startDestinations()
                return START_NOT_STICKY
            }
            // Lightweight actions — service is already foreground
            "MUTE_AUDIO" -> {
                masterDisplay?.disableAudio()
                return START_NOT_STICKY
            }
            "UNMUTE_AUDIO" -> {
                masterDisplay?.enableAudio()
                return START_NOT_STICKY
            }
            "ENABLE_PRIVACY" -> {
                Log.d(TAG, "ENABLE_PRIVACY - requires encoder integration")
                return START_NOT_STICKY
            }
            "DISABLE_PRIVACY" -> {
                Log.d(TAG, "DISABLE_PRIVACY")
                return START_NOT_STICKY
            }
            "CREATE_CLIP" -> {
                clipManager?.saveClip()
                return START_NOT_STICKY
            }
            "SET_MIC_VOLUME", "SET_INTERNAL_VOLUME" -> {
                // Volume actions — handled by encoder, no-op if not streaming
                return START_NOT_STICKY
            }
        }

        // ── Below is the initial start with MediaProjection data from LiveScreen ──
        // This is the ONLY path that calls startForegroundNotification().
        startForegroundNotification()

        // CRITICAL: Activity.RESULT_OK is -1, so we CANNOT use -1 as the default
        // sentinel. Use Int.MIN_VALUE to distinguish "extra not provided" from RESULT_OK.
        val resultCode = intent.getIntExtra("RESULT_CODE", Int.MIN_VALUE)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>("DATA")
        }
        val streamKey = intent.getStringExtra("STREAM_KEY") ?: repository.getStreamKey()
        val saveVod = intent.getBooleanExtra("SAVE_VOD", false)

        Log.d(TAG, "onStartCommand: resultCode=$resultCode, data=${data != null}, streamKey=${streamKey?.take(4)}...")

        if (resultCode != Int.MIN_VALUE && data != null) {
            currentStreamKey = streamKey ?: ""
            shouldSaveVod = saveVod
            intentData = resultCode to data

            Log.d(TAG, "Starting Overlay services and Destinations")
            
            // Proactive check for Overlay permission
            if (!Settings.canDrawOverlays(this)) {
                Log.e(TAG, "Overlay Permission is MISING! Cannot show floating icon.")
                Toast.makeText(this, "OVERLAY PERMISSION MISSING: Enable 'Display over other apps' in Settings.", Toast.LENGTH_LONG).show()
            }

            try {
                val overlayIntent = Intent(this, FloatingOverlayService::class.java).apply { action = "SHOW_OVERLAY"; putExtra("OVERLAY_MODE", "STREAM") }
                ContextCompat.startForegroundService(this, overlayIntent)
                
                // CRITICAL FIX: Consume the MediaProjection intend immediately on Android 14+
                // Using the token immediately prevents SecurityException if the user delays
                // starting the stream via the floating icon.
                val projectionManager = getSystemService(MediaProjectionManager::class.java)
                activeMediaProjection = projectionManager.getMediaProjection(resultCode, data)
                activeMediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopStreaming()
                    }
                }, Handler(Looper.getMainLooper()))
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Overlay Service or initialize MediaProjection", e)
            }
            
            // Don't auto-start streaming — let the user tap the floating icon
            Log.d(TAG, "Overlay launched. Waiting for user to tap icon to start stream.")
        } else if (resultCode != Int.MIN_VALUE || data != null) {
            Log.e(TAG, "StreamingService: Started with partial data (resultCode=$resultCode, data=${data != null}).")
            stopStreaming()
        } else if (intent.action == null) {
            Log.e(TAG, "StreamingService: Started with no data and no action.")
            stopStreaming()
        }

        return START_NOT_STICKY
    }

    // ── Core streaming logic ────────────────────────────────────────

    private fun startDestinations() {
        val intentPair = intentData
        if (intentPair == null) {
            Log.e(TAG, "startDestinations: intentData is NULL — MediaProjection result was never stored. Cannot start stream.")
            Toast.makeText(applicationContext, "Error: Screen capture permission missing. Please restart stream.", Toast.LENGTH_LONG).show()
            return
        }
        val (resCode, resData) = intentPair

        serviceScope?.launch {
            val quality = streamDataStore.streamQualityFlow.first()
            val fps = streamDataStore.streamFpsFlow.first()
            val bitrate = streamDataStore.streamBitrateFlow.first()
            val youtubeEnabled = streamDataStore.youtubeEnabledFlow.first()
            
            var youtubeKey = ""
            if (youtubeEnabled) {
                val token = secureStorageManager.youtubeAccessToken
                if (token.isNotEmpty()) {
                    Log.d(TAG, "Fetching YouTube Stream Key and Creating Broadcast...")
                    val streamData = youtubeApiManager.fetchStreamKey(token)
                    if (streamData != null) {
                        val streamId = streamData.first
                        youtubeRtmpUrl = streamData.second.first
                        youtubeKey = streamData.second.second
                        youtubeStreamKeyDynamic = youtubeKey // Store for secondary/retry use
                        
                        val title = streamDataStore.streamTitleFlow.first()
                        val categoryField = streamDataStore.selectedGameNameFlow.first()
                        val category = if (categoryField.isEmpty()) "Gaming" else categoryField
                        
                        youtubeBroadcastId = youtubeApiManager.createAndBindBroadcast(token, title, category, streamId)
                        Log.d(TAG, "YouTube Broadcast ID: $youtubeBroadcastId")
                    } else {
                        Log.e(TAG, "Failed to fetch YouTube stream data")
                        StreamStateManager.updateDestinationStatus("YOUTUBE", StreamStateManager.ConnectionStatus.FAILED)
                        // If YouTube was the only destination, we map to Twitch failed fallback later,
                        // but setting status here helps UI.
                    }
                }
            }

            val aspectRatio = streamDataStore.aspectRatioFlow.first()
            val twitchKey = currentStreamKey
            
            // ── Read enabled states ──
            val twitchEnabled = streamDataStore.twitchEnabledFlow.first()
            val kickEnabled = streamDataStore.kickEnabledFlow.first()
            val kickKey = secureStorageManager.kickStreamKey
            val kickUrl = secureStorageManager.kickRtmpUrl

            Log.d(TAG, "youtubeEnabled: $youtubeEnabled, youtubeKey length: ${youtubeKey.length}")
            Log.d(TAG, "twitchEnabled: $twitchEnabled, twitchKey length: ${twitchKey.length}")
            Log.d(TAG, "kickEnabled: $kickEnabled, kickKey length: ${kickKey.length}")

            val (baseWidth, baseHeight) = when (quality) {
                "1080p" -> 1920 to 1080
                "720p" -> 1280 to 720
                "480p" -> 854 to 480
                else -> 1280 to 720
            }

            val (width, height) = if (aspectRatio == "portrait") {
                baseHeight to baseWidth
            } else {
                baseWidth to baseHeight
            }

            withContext(Dispatchers.Main) {
                try {
                    // ── Select Primary & Secondaries ──
                    secondaryPlatforms.clear()
                    when {
                        twitchEnabled && twitchKey.isNotEmpty() -> {
                            Log.d(TAG, "Selection: Twitch is Primary")
                            primaryPlatform = "TWITCH"
                            if (youtubeEnabled && youtubeKey.isNotEmpty()) {
                                Log.d(TAG, "Selection: YouTube is Secondary")
                                secondaryPlatforms.add("YOUTUBE")
                            }
                            if (kickEnabled && kickKey.isNotEmpty() && kickUrl.isNotEmpty()) {
                                Log.d(TAG, "Selection: Kick is Secondary")
                                secondaryPlatforms.add("KICK")
                            }
                        }
                        youtubeEnabled && youtubeKey.isNotEmpty() -> {
                            Log.d(TAG, "Selection: YouTube is Primary")
                            primaryPlatform = "YOUTUBE"
                            if (kickEnabled && kickKey.isNotEmpty() && kickUrl.isNotEmpty()) {
                                Log.d(TAG, "Selection: Kick is Secondary")
                                secondaryPlatforms.add("KICK")
                            }
                        }
                        kickEnabled && kickKey.isNotEmpty() && kickUrl.isNotEmpty() -> {
                            Log.d(TAG, "Selection: Kick is Primary")
                            primaryPlatform = "KICK"
                        }
                        else -> {
                            Log.e(TAG, "No destinations enabled or keys missing. twitch=($twitchEnabled, key=${twitchKey.isNotEmpty()}), youtube=($youtubeEnabled, key=${youtubeKey.isNotEmpty()}), kick=($kickEnabled, key=${kickKey.isNotEmpty()})")
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(applicationContext, "Stream failed: No platform is configured. Check Settings.", Toast.LENGTH_LONG).show()
                            }
                            StreamStateManager.updateDestinationStatus(primaryPlatform, StreamStateManager.ConnectionStatus.FAILED)
                            return@withContext
                        }
                    }

                    // ── Create single MultiRtmpDisplay (if not already) ──
                    if (masterDisplay == null) {
                        val display = MultiRtmpDisplay(
                            applicationContext,
                            createConnectChecker(primaryPlatform)
                        )
                        val vPrep = display.prepareVideo(
                            width, height, fps, bitrate, 0, AppConfig.KEYFRAME_INTERVAL
                        )
                        val aPrep = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && activeMediaProjection != null) {
                            Log.d(TAG, "Master Prep: Using Internal Audio Capture (API 29+)")
                            // When useMaster is true, prepareAudio without a source uses the internal capture
                            display.prepareAudio(
                                AppConfig.AUDIO_BITRATE, AppConfig.AUDIO_SAMPLE_RATE,
                                true, true, true
                            )
                        } else {
                            Log.d(TAG, "Master Prep: Using Microphone Capture")
                            display.prepareAudio(
                                android.media.MediaRecorder.AudioSource.MIC,
                                AppConfig.AUDIO_BITRATE, AppConfig.AUDIO_SAMPLE_RATE,
                                true, true, true
                            )
                        }
                        Log.d(TAG, "Master Prep ($primaryPlatform): video=$vPrep, audio=$aPrep")
                        if (!vPrep || !aPrep) {
                            Log.e(TAG, "Master ($primaryPlatform): Preparation failed")
                            StreamStateManager.updateDestinationStatus(primaryPlatform, StreamStateManager.ConnectionStatus.FAILED)
                            return@withContext
                        }

                        // MediaProjection was already consumed natively in onStartCommand.
                        // Pedro SG94 might internally cast to projection token, we pass the raw intent again
                        // as a fallback for internal VirtualDisplay creation.
                        try {
                            display.setIntentResult(resCode, resData)
                            masterDisplay = display
                        } catch (e: SecurityException) {
                            Log.e(TAG, "MediaProjection token rejected by Pedro SDK", e)
                            StreamStateManager.updateDestinationStatus(primaryPlatform, StreamStateManager.ConnectionStatus.FAILED)
                            return@withContext
                        }
                    }

                    // ── Register secondary destinations ───────
                    secondaryPlatforms.forEach { platform ->
                        val url = when (platform) {
                            "YOUTUBE" -> "$youtubeRtmpUrl/$youtubeKey"
                            "KICK" -> "$kickUrl/$kickKey"
                            else -> ""
                        }
                        if (url.isNotEmpty()) {
                            val client = RtmpClient(createConnectChecker(platform))
                            masterDisplay?.addSecondaryClient(platform, client)
                            Log.d(TAG, "$platform registered as secondary client")
                        }
                    }

                    // ── Start primary stream ────────────────────
                    val masterUrl = when (primaryPlatform) {
                        "TWITCH" -> "${AppConfig.RTMP_BASE_URL}$twitchKey"
                        "YOUTUBE" -> "rtmp://a.rtmp.youtube.com/live2/$youtubeKey"
                        "KICK" -> "$kickUrl/$kickKey"
                        else -> ""
                    }
                    val maskedUrl = if (masterUrl.length > 30) masterUrl.take(30) + "..." else masterUrl
                    Log.d(TAG, "Master URL for $primaryPlatform: $maskedUrl")
                    masterDisplay?.startStream(masterUrl)
                    StreamStateManager.updateDestinationStatus(primaryPlatform, StreamStateManager.ConnectionStatus.CONNECTING)
                    
                    // Keep screen awake while streaming
                    acquireWakeLock()

                } catch (e: Exception) {
                    Log.e(TAG, "Error starting streams", e)
                    StreamStateManager.updateDestinationStatus("TWITCH", StreamStateManager.ConnectionStatus.FAILED)
                }
            }
        }
    }

    /**
     * Connect secondary clients.
     * Called after the Master connects successfully.
     */
    private fun connectSecondaries() {
        val youtubeKey = youtubeStreamKeyDynamic
        val kickKey = secureStorageManager.kickStreamKey
        val kickUrl = secureStorageManager.kickRtmpUrl

        secondaryPlatforms.forEach { platform ->
            val url = when (platform) {
                "YOUTUBE" -> "rtmp://a.rtmp.youtube.com/live2/$youtubeKey"
                "KICK" -> "$kickUrl/$kickKey"
                else -> ""
            }
            if (url.isNotEmpty()) {
                Log.d(TAG, "Connecting $platform to: $url")
                StreamStateManager.updateDestinationStatus(platform, StreamStateManager.ConnectionStatus.CONNECTING)
                masterDisplay?.connectSecondary(platform, url)
            }
        }
    }

    /**
     * Retry YouTube independently without affecting Twitch.
     */
    private fun retryYouTube() {
        youtubeRetryCount++
        if (youtubeRetryCount > MAX_RETRIES) {
            Log.e(TAG, "YouTube: Max retries ($MAX_RETRIES) exceeded. Giving up.")
            StreamStateManager.updateDestinationStatus("YOUTUBE", StreamStateManager.ConnectionStatus.FAILED)
            return
        }
        // Notify UI that YouTube is actively retrying
        StreamStateManager.updateDestinationStatus("YOUTUBE", StreamStateManager.ConnectionStatus.RETRYING)
        val backoffTime = (Math.pow(2.0, youtubeRetryCount.toDouble()) * 1000).toLong()
        Log.d(TAG, "YouTube: Retry #$youtubeRetryCount/$MAX_RETRIES in ${backoffTime}ms")
        
        Handler(Looper.getMainLooper()).postDelayed({
            if (StreamStateManager._isLive.value) {
                if (primaryPlatform == "YOUTUBE") {
                    Log.d(TAG, "YouTube: Retrying as Primary")
                    if (youtubeStreamKeyDynamic.isNotEmpty()) {
                        val url = "$youtubeRtmpUrl/$youtubeStreamKeyDynamic"
                        masterDisplay?.startStream(url)
                    }
                } else {
                    Log.d(TAG, "YouTube: Retrying as Secondary")
                    masterDisplay?.disconnectSecondary("YOUTUBE")
                    connectSecondaries()
                }
            }
        }, backoffTime)
    }

    /**
     * Retry Twitch. Since Twitch is the Master stream, a retry reconnects
     * the entire primary RtmpClient but does NOT recreate the encoder.
     * If Twitch exceeds max retries, it marks FAILED but does NOT stop
     * the service — YouTube may still be streaming (true failover).
     */
    private fun retryTwitch() {
        twitchRetryCount++
        if (twitchRetryCount > MAX_RETRIES) {
            Log.e(TAG, "Twitch: Max retries ($MAX_RETRIES) exceeded. Marking failed.")
            StreamStateManager.updateDestinationStatus("TWITCH", StreamStateManager.ConnectionStatus.FAILED)
            StreamStateManager._isLive.value = false
            // Only stop everything if no other platforms are streaming
            val anySecondaryAlive = secondaryPlatforms.any { masterDisplay?.isSecondaryStreaming(it) == true }
            if (!anySecondaryAlive) {
                Log.d(TAG, "No platforms alive. Stopping service.")
                stopStreaming()
            }
            return
        }
        // Notify UI that Twitch is actively retrying
        StreamStateManager.updateDestinationStatus("TWITCH", StreamStateManager.ConnectionStatus.RETRYING)
        val backoffTime = (Math.pow(2.0, twitchRetryCount.toDouble()) * 1000).toLong()
        Log.d(TAG, "Twitch: Retry #$twitchRetryCount/$MAX_RETRIES in ${backoffTime}ms")

        Handler(Looper.getMainLooper()).postDelayed({
            if (currentStreamKey.isNotEmpty() && (primaryPlatform == "TWITCH" || secondaryPlatforms.contains("TWITCH"))) {
                val twitchUrl = "${AppConfig.RTMP_BASE_URL}$currentStreamKey"
                masterDisplay?.startStream(twitchUrl)
                StreamStateManager.updateDestinationStatus("TWITCH", StreamStateManager.ConnectionStatus.CONNECTING)
            }
        }, backoffTime)
    }

    // ── ConnectChecker and Handlers ────────────────────────────────

    private fun createConnectChecker(name: String): ConnectChecker {
        return object : ConnectChecker {
            override fun onConnectionStarted(url: String) {
                Log.d(TAG, "CHECKER: $name connection started")
            }

            override fun onConnectionSuccess() {
                Log.d(TAG, "CHECKER: $name connection success")
                this@StreamingService.onConnectionSuccess(name)
            }

            override fun onConnectionFailed(reason: String) {
                Log.e(TAG, "CHECKER: $name connection failed: $reason")
                this@StreamingService.onConnectionFailed(name, reason)
            }

            override fun onNewBitrate(bitrate: Long) {
                if (name == primaryPlatform) {
                    StreamStateManager.updateBitrate((bitrate / 1024).toInt())
                }
            }

            override fun onDisconnect() {
                Log.d(TAG, "CHECKER: $name disconnect")
                this@StreamingService.onDisconnect(name)
            }

            override fun onAuthError() {
                Log.e(TAG, "CHECKER: $name auth error")
            }

            override fun onAuthSuccess() {
                Log.d(TAG, "CHECKER: $name auth success")
            }
        }
    }

    private fun onConnectionSuccess(name: String) {
        Log.d(TAG, "onConnectionSuccess: $name connected successfully")
        StreamStateManager.updateDestinationStatus(name, StreamStateManager.ConnectionStatus.CONNECTED)

        if (name == primaryPlatform) {
            StreamStateManager._isLive.value = true
            twitchRetryCount = 0
            startDurationTimer()
            try {
                val overlayIntent = Intent(applicationContext, FloatingOverlayService::class.java).apply {
                    action = "SHOW_OVERLAY"
                    putExtra("OVERLAY_MODE", "STREAM")
                }
                applicationContext.startService(overlayIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update Overlay Service", e)
            }
            
            // Start Unified Chat Service based on enabled platforms
            val chatIntent = Intent(applicationContext, UnifiedChatService::class.java).apply {
                action = "START_CHAT"
                putExtra("TWITCH_ENABLED", primaryPlatform == "TWITCH" || secondaryPlatforms.contains("TWITCH"))
                putExtra("YOUTUBE_ENABLED", primaryPlatform == "YOUTUBE" || secondaryPlatforms.contains("YOUTUBE"))
                putExtra("KICK_ENABLED", primaryPlatform == "KICK" || secondaryPlatforms.contains("KICK"))
            }
            startService(chatIntent)
            
            acquireWakeLock()

            serviceScope?.let { scope ->
                clipManager = ClipManager(applicationContext, scope)
                masterDisplay?.let { display ->
                    clipManager?.startContinuousRecording(display)
                }
            }

            if (shouldSaveVod && masterDisplay?.isRecording == false) {
                try {
                    val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "StreamTwin")
                    folder.mkdirs()
                    val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    val file = File(folder, "VOD_${sdf.format(Date())}.mp4")
                    masterDisplay?.startRecord(file.absolutePath) { status ->
                        Log.d(TAG, "Recording status: $status")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "VOD Recording failed", e)
                }
            }

            connectSecondaries()
            
            // ── CRITICAL: Re-sync headers for YouTube/Twitch Primary ──
            // If the encoder was already running, the new connection missed the SPS/PPS.
            masterDisplay?.syncPrimary()
        } else {
            Log.d(TAG, "$name connected successfully as secondary")
            masterDisplay?.syncSecondary(name)
        }

        if (name == "YOUTUBE" && youtubeBroadcastId != null) {
            serviceScope?.launch {
                Log.d(TAG, "Transitioning YouTube stream to LIVE")
                youtubeApiManager.transitionToLive(secureStorageManager.youtubeAccessToken, youtubeBroadcastId!!)
            }
        }
    }

    private fun onConnectionFailed(name: String, reason: String) {
        Log.e(TAG, "onConnectionFailed: $name failed. Reason: $reason")
        StreamStateManager.updateDestinationStatus(name, StreamStateManager.ConnectionStatus.FAILED)

        when (name) {
            "TWITCH" -> retryTwitch()
            "YOUTUBE" -> retryYouTube()
        }
    }

    private fun onDisconnect(name: String) {
        Log.d(TAG, "onDisconnect: $name disconnected")
        StreamStateManager.updateDestinationStatus(name, StreamStateManager.ConnectionStatus.DISCONNECTED)

        when (name) {
            "TWITCH" -> {
                if (StreamStateManager._isLive.value) {
                    retryTwitch()
                }
            }
            "YOUTUBE" -> {
                if (StreamStateManager._isLive.value) {
                    retryYouTube()
                }
            }
        }
    }

    // ── Duration timer ──────────────────────────────────────────────

    private fun startDurationTimer() {
        serviceScope?.launch {
            var seconds = 0L
            while (isActive && StreamStateManager._isLive.value) {
                delay(1000)
                seconds++
                StreamStateManager._streamDuration.value = seconds
            }
        }
    }

    // ── Stop everything ─────────────────────────────────────────────

    private fun stopStreaming() {
        if (youtubeBroadcastId != null) {
            val token = secureStorageManager.youtubeAccessToken
            val bId = youtubeBroadcastId!!
            serviceScope?.launch {
                Log.d(TAG, "Transitioning YouTube stream to COMPLETE")
                youtubeApiManager.transitionToComplete(token, bId)
            }
        }
        youtubeBroadcastId = null

        masterDisplay?.stopStream()
        masterDisplay = null
        activeMediaProjection?.stop()
        activeMediaProjection = null
        clipManager?.stopContinuousRecording()
        clipManager = null
        StreamStateManager.resetData()
        twitchRetryCount = 0
        youtubeRetryCount = 0
        youtubeRtmpUrl = ""
        youtubeStreamKeyDynamic = ""
        serviceScope?.cancel()
        releaseWakeLock()
        // Use the STOP_OVERLAY action so FloatingOverlayService resets savedMode before stopping.
        try {
            startService(Intent(this, FloatingOverlayService::class.java).apply { action = "STOP_OVERLAY" })
        } catch (e: Exception) {
            // Fallback: direct stop if startService fails (e.g. empty back-stack)
            stopService(Intent(this, FloatingOverlayService::class.java))
        }
        startService(Intent(this, UnifiedChatService::class.java).apply { action = "STOP_CHAT" })
        stopForeground(true)
        stopSelf()
    }

    // ── Notification ────────────────────────────────────────────────

    private fun startForegroundNotification() {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopPendingIntent = PendingIntent.getService(this, 1, Intent(this, StreamingService::class.java).apply { action = "STOP_STREAM" }, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("StreamTwin is streaming")
            .setContentText("Broadcasting live")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(0, "End Stream", stopPendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Use MEDIA_PROJECTION only. Do NOT add FOREGROUND_SERVICE_TYPE_MICROPHONE here:
                // Android 12+ requires the app to be in a foreground-eligible state when using the
                // microphone FGS type, but the MediaProjection dialog backgrounds the activity
                // at the exact moment startForeground() is called, causing a SecurityException.
                // Audio capture via RTMP / AudioRecord / Pedro SDK does not require the FGS type.
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground: ${e.message}")
            try {
                // Last-resort: try without any FGS type
                startForeground(1, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "Completely failed to start foreground", e2)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Stream Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            // Use PARTIAL_WAKE_LOCK to keep CPU alive even if screen turns off.
            // Combined with ON_AFTER_RELEASE (though behavior varies by OS) and custom TAG.
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StreamTwin:StreamingWakeLock")
        }
        if (wakeLock?.isHeld == false) {
            // No timeout (or very long one) to prevent locking during long streams
            wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 hours
            Log.d(TAG, "WakeLock acquired (Partial)")
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }
}
