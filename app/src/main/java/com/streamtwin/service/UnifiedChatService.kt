package com.streamtwin.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class UnifiedChatService : Service() {

    private val TAG = "UnifiedChatService"

    companion object {
        const val PLATFORM_TWITCH = "TWITCH"
        const val PLATFORM_YOUTUBE = "YOUTUBE"
        const val PLATFORM_KICK = "KICK"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        
        when (action) {
            "START_CHAT" -> {
                Log.d(TAG, "Starting unified chat services...")
                val twitchEnabled = intent.getBooleanExtra("TWITCH_ENABLED", false)
                val youtubeEnabled = intent.getBooleanExtra("YOUTUBE_ENABLED", false)
                val kickEnabled = intent.getBooleanExtra("KICK_ENABLED", false)
                
                if (twitchEnabled) {
                    startPlatformChat(PLATFORM_TWITCH)
                }
                
                if (youtubeEnabled) {
                    startPlatformChat(PLATFORM_YOUTUBE)
                }
                
                // TODO: Start Kick when its specific chat service is implemented
            }
            "STOP_CHAT" -> {
                Log.d(TAG, "Stopping all chat services...")
                stopPlatformChat(PLATFORM_TWITCH)
                stopPlatformChat(PLATFORM_YOUTUBE)
                stopSelf()
            }
        }
        
        return START_NOT_STICKY
    }

    private fun startPlatformChat(platformId: String) {
        val intent = when (platformId) {
            PLATFORM_TWITCH -> Intent(this, TwitchChatService::class.java).apply { action = "START_CHAT" }
            PLATFORM_YOUTUBE -> Intent(this, YouTubeChatService::class.java).apply { action = "START_CHAT" }
            else -> null
        }
        
        intent?.let {
            startService(it)
            Log.d(TAG, "Started chat service for $platformId")
        }
    }

    private fun stopPlatformChat(platformId: String) {
        val intent = when (platformId) {
            PLATFORM_TWITCH -> Intent(this, TwitchChatService::class.java).apply { action = "STOP_CHAT" }
            PLATFORM_YOUTUBE -> Intent(this, YouTubeChatService::class.java).apply { action = "STOP_CHAT" }
            else -> null
        }
        
        intent?.let {
            startService(it)
            Log.d(TAG, "Stopped chat service for $platformId")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
