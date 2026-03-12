package com.streamtwin.domain.model

data class StreamConfig(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val videoBitrate: Int = 2500 * 1024,
    val audioBitrate: Int = 128 * 1024,
    val audioSampleRate: Int = 44100,
    val keyframeInterval: Int = 2
)
