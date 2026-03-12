package com.streamtwin.util

object AppConfig {
    const val TWITCH_CLIENT_ID = "jncx43az52l4e4zgizcaq0v8t9e7i9"
    const val TWITCH_REDIRECT_URI = "https://localhost/streamtwin/callback"
    const val TWITCH_AUTH_URL = "https://id.twitch.tv/oauth2/authorize"
    const val TWITCH_API_BASE = "https://api.twitch.tv/helix/"
    const val RTMP_BASE_URL = "rtmps://live.twitch.tv:443/app/"
    const val STREAM_WIDTH = 1920
    const val STREAM_HEIGHT = 1080
    const val STREAM_FPS = 60
    const val STREAM_BITRATE = 6000 * 1024  // 6000 kbps
    const val AUDIO_BITRATE = 128 * 1024
    const val AUDIO_SAMPLE_RATE = 44100
    const val KEYFRAME_INTERVAL = 2         // seconds
}
