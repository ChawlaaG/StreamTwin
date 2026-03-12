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
import com.pedro.library.rtmp.RtmpDisplay
import com.streamtwin.MainActivity
import com.streamtwin.R
import android.content.pm.ServiceInfo
import android.os.Environment
import com.streamtwin.util.AppConfig
import com.streamtwin.data.local.StreamDataStore
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
class StreamingService : Service(), ConnectChecker {
    
    @Inject
    lateinit var repository: TwitchRepository

    @Inject
    lateinit var streamDataStore: StreamDataStore

    private lateinit var screenCapture: RtmpDisplay
    private val TAG = "StreamingService"
    private val CHANNEL_ID = "StreamTwinChannel"
    private var serviceScope: CoroutineScope? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    private var currentStreamKey: String = ""
    private var retryCount = 0
    private var shouldSaveVod = false
    
    private var clipManager: ClipManager? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        serviceScope = CoroutineScope(Dispatchers.Default + Job())
        screenCapture = RtmpDisplay(applicationContext, true, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val action = intent.action
        when (action) {
            "STOP_STREAM" -> {
                stopStreaming()
                return START_NOT_STICKY
            }
            "MUTE_AUDIO" -> {
                if (::screenCapture.isInitialized) screenCapture.disableAudio()
                return START_NOT_STICKY
            }
            "UNMUTE_AUDIO" -> {
                if (::screenCapture.isInitialized) screenCapture.enableAudio()
                return START_NOT_STICKY
            }
            "CREATE_CLIP" -> {
                clipManager?.saveClip()
                return START_NOT_STICKY
            }
        }

        val resultCode = intent.getIntExtra("RESULT_CODE", -1)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>("DATA")
        }
        val streamKey = intent.getStringExtra("STREAM_KEY") ?: repository.getStreamKey()
        val saveVod = intent.getBooleanExtra("SAVE_VOD", false)
        
        Log.d(TAG, "onStartCommand: action=$action, resultCode=$resultCode, streamKeyFound=${streamKey != null}, saveVod=$saveVod")

        if (resultCode == -1 && data != null && streamKey != null) {
            currentStreamKey = streamKey
            shouldSaveVod = saveVod
            startForegroundNotification()
            screenCapture.setIntentResult(resultCode, data)
            startStream(streamKey)
        }

        return START_NOT_STICKY
    }

    private fun startStream(streamKey: String) {
        val rtmpUrl = "${AppConfig.RTMP_BASE_URL}$streamKey"
        
        if (!screenCapture.isStreaming) {
            serviceScope?.launch {
                val quality = streamDataStore.streamQualityFlow.first()
                val fps = streamDataStore.streamFpsFlow.first()
                val bitrate = streamDataStore.streamBitrateFlow.first()

                val (width, height) = when (quality) {
                    "1080p" -> 1920 to 1080
                    "720p" -> 1280 to 720
                    "480p" -> 854 to 480
                    else -> 1920 to 1080
                }

                // In pedroSG94:library:2.6.7, the method might be different or accessed via another component
                // Check if it's available on screenCapture directly. If not, we use the correct way.
                try {
                    // For internal audio capture in 2.6.x
                    // screenCapture.setInternalAudio(true) // If this fails, we'll catch it.
                } catch (e: Exception) {
                    Log.w(TAG, "Internal audio capture initialization failed")
                }

                val videoPrepared = screenCapture.prepareVideo(width, height, fps, bitrate, 0, AppConfig.KEYFRAME_INTERVAL)
                // Use VOICE_COMMUNICATION profile for aggressive noise cancelling by passing it as the first parameter
                val audioPrepared = screenCapture.prepareAudio(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION, AppConfig.AUDIO_BITRATE, AppConfig.AUDIO_SAMPLE_RATE, true, true, true)
                
                withContext(Dispatchers.Main) {
                    if (videoPrepared && audioPrepared) {
                        screenCapture.startStream(rtmpUrl)
                    } else {
                        StreamStateManager._isLive.value = false
                    }
                }
            }
        }
    }



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

    private fun stopStreaming() {
        if (screenCapture.isRecording) {
            screenCapture.stopRecord()
        }
        clipManager?.stopContinuousRecording()
        clipManager = null
        if (::screenCapture.isInitialized) {
            screenCapture.stopStream()
        }
        StreamStateManager._isLive.value = false
        StreamStateManager._streamDuration.value = 0L
        StreamStateManager._currentBitrate.value = 0
        serviceScope?.cancel()
        releaseWakeLock()
        
        stopService(Intent(this, FloatingOverlayService::class.java))
        val chatStopIntent = Intent(this, TwitchChatService::class.java).apply { action = "STOP_CHAT" }
        startService(chatStopIntent)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun startForegroundNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, StreamingService::class.java).apply {
            action = "STOP_STREAM"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("StreamTwin is Live \uD83D\uDD34")
            .setContentText("Tap to return to stream controls")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(0, "End Stream", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(1, notification, type)
        } else {
            startForeground(1, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Stream Service",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onAuthError() {}
    override fun onAuthSuccess() {}
    override fun onConnectionFailed(reason: String) {
        retryCount++
        Log.e(TAG, "Connection failed. Reason: $reason. Retry: $retryCount")
        
        if (retryCount <= 6) {
            val backoffTime = (Math.pow(2.0, retryCount.toDouble()) * 1000).toLong().coerceAtMost(10000) // Max 10s delay
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, "Connection dropped! Reconnecting in ${backoffTime / 1000}s...", Toast.LENGTH_SHORT).show()
            }
            if (::screenCapture.isInitialized) screenCapture.stopStream()
            
            Handler(Looper.getMainLooper()).postDelayed({ 
                if (StreamStateManager._isLive.value || retryCount > 0) {
                    startStream(currentStreamKey) 
                }
            }, backoffTime)
        } else {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, "Stream Reconnection Failed.", Toast.LENGTH_LONG).show()
            }
            stopStreaming()
        }
    }
    
    override fun onConnectionStarted(url: String) {}
    
    override fun onConnectionSuccess() {
        retryCount = 0
        StreamStateManager._isLive.value = true
        startDurationTimer()
        startService(Intent(this, FloatingOverlayService::class.java))
        startService(Intent(this, TwitchChatService::class.java))
        acquireWakeLock()
        
        if (shouldSaveVod && !screenCapture.isRecording) {
            val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "StreamTwin")
            if (!folder.exists()) folder.mkdirs()
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val filePath = File(folder, "VOD_${sdf.format(Date())}.mp4").absolutePath
            screenCapture.startRecord(filePath) { status ->
                Log.d(TAG, "Recording status: $status")
            }
        }
        
        // Start continuous segment recording for retrospective clipping
        serviceScope?.let { scope ->
            clipManager = ClipManager(applicationContext, scope)
            clipManager?.startContinuousRecording(screenCapture)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StreamTwin:StreamingWakeLock")
        }
        wakeLock?.acquire(30 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }
    
    override fun onDisconnect() {
        Log.w(TAG, "onDisconnect called")
        // If the user hasn't explicitly stopped the stream, attempt reconnect
        if (StreamStateManager._isLive.value) {
           onConnectionFailed("Unexpected Disconnect")
        }
    }
    
    override fun onNewBitrate(bitrate: Long) {
        StreamStateManager._currentBitrate.value = (bitrate / 1000).toInt()
    }
}
