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

    // Stream Health
    val _streamHealth = MutableStateFlow(StreamHealth.EXCELLENT)
    val streamHealth: StateFlow<StreamHealth> = _streamHealth
    
    val _droppedFrames = MutableStateFlow(0)
    val droppedFrames: StateFlow<Int> = _droppedFrames
    
    // Post-Stream Summary Data
    val _peakBitrate = MutableStateFlow(0)
    val _clipsSaved = MutableStateFlow(0)
    private val bitrateReadings = mutableListOf<Int>()

    val _destinationStatuses = MutableStateFlow<Map<String, ConnectionStatus>>(
        mapOf(
            "TWITCH" to ConnectionStatus.DISCONNECTED,
            "KICK" to ConnectionStatus.DISCONNECTED,
            "YOUTUBE" to ConnectionStatus.DISCONNECTED
        )
    )
    val destinationStatuses: StateFlow<Map<String, ConnectionStatus>> = _destinationStatuses

    fun saveStreamTitle(title: String) {
        _streamTitle.value = title
    }

    fun updateDestinationStatus(destination: String, status: ConnectionStatus) {
        val current = _destinationStatuses.value.toMutableMap()
        current[destination] = status
        _destinationStatuses.value = current
    }

    enum class ConnectionStatus {
        DISCONNECTED, CONNECTING, CONNECTED, RETRYING, FAILED
    }

    enum class StreamHealth {
        EXCELLENT, GOOD, FAIR, POOR, CRITICAL
    }

    fun updateBitrate(bitrateKbps: Int) {
        _currentBitrate.value = bitrateKbps
        
        if (bitrateKbps > 0) {
            bitrateReadings.add(bitrateKbps)
            if (bitrateKbps > _peakBitrate.value) {
                _peakBitrate.value = bitrateKbps
            }
        }
        
        // Simple health calculation (can be improved with rolling averages)
        _streamHealth.value = when {
            bitrateKbps == 0 && _isLive.value -> StreamHealth.CRITICAL
            bitrateKbps > 4000 -> StreamHealth.EXCELLENT
            bitrateKbps > 2500 -> StreamHealth.GOOD
            bitrateKbps > 1000 -> StreamHealth.FAIR
            else -> StreamHealth.POOR
        }
    }

    fun incrementDroppedFrames() {
        _droppedFrames.value += 1
    }
    
    fun incrementClipsSaved() {
        _clipsSaved.value += 1
    }

    fun getStreamSummary(): StreamSummary {
        val avgBitrate = if (bitrateReadings.isNotEmpty()) bitrateReadings.average().toInt() else 0
        return StreamSummary(
            durationSeconds = _streamDuration.value,
            averageBitrate = avgBitrate,
            peakBitrate = _peakBitrate.value,
            clipsSaved = _clipsSaved.value,
            finalStatuses = _destinationStatuses.value.toMap()
        )
    }

    fun resetData() {
        _isLive.value = false
        _streamDuration.value = 0L
        _currentBitrate.value = 0
        _peakBitrate.value = 0
        _droppedFrames.value = 0
        _clipsSaved.value = 0
        bitrateReadings.clear()
        _streamHealth.value = StreamHealth.EXCELLENT
        _destinationStatuses.value = mapOf(
            "TWITCH" to ConnectionStatus.DISCONNECTED,
            "KICK" to ConnectionStatus.DISCONNECTED,
            "YOUTUBE" to ConnectionStatus.DISCONNECTED
        )
    }
}

data class StreamSummary(
    val durationSeconds: Long,
    val averageBitrate: Int,
    val peakBitrate: Int,
    val clipsSaved: Int,
    val finalStatuses: Map<String, StreamStateManager.ConnectionStatus>
)
