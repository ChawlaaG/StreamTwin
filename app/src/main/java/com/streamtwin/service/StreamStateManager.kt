package com.streamtwin.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object StreamStateManager {
    val _isLive = MutableStateFlow(false)
    val isLive: StateFlow<Boolean> = _isLive

    val _currentBitrate = MutableStateFlow(0)
    val currentBitrate: StateFlow<Int> = _currentBitrate
    
    val _streamDuration = MutableStateFlow(0L) // in seconds
    val streamDuration: StateFlow<Long> = _streamDuration

    val _streamTitle = MutableStateFlow("Live Stream")
    val streamTitle: StateFlow<String> = _streamTitle

    fun saveStreamTitle(title: String) {
        _streamTitle.value = title
    }
}
