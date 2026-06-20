package com.streamtwin.data.clip

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClipRepository @Inject constructor() {

    private val _isClipModeActive = MutableStateFlow(false)
    val isClipModeActive: StateFlow<Boolean> = _isClipModeActive.asStateFlow()

    private val _clipReady = MutableStateFlow(false)
    val clipReady: StateFlow<Boolean> = _clipReady.asStateFlow()

    private val _clipSavedEvent = MutableSharedFlow<String>()
    val clipSavedEvent: SharedFlow<String> = _clipSavedEvent.asSharedFlow()

    /** True when a clip save has been queued (requested while engine was starting). */
    private val _queuedSave = MutableStateFlow(false)
    val queuedSave: StateFlow<Boolean> = _queuedSave.asStateFlow()

    fun setClipModeActive(active: Boolean) {
        _isClipModeActive.value = active
        // Clear any queued save when mode is deactivated
        if (!active) _queuedSave.value = false
    }

    fun setClipReady(ready: Boolean) {
        _clipReady.value = ready
    }

    fun setQueuedSave(queued: Boolean) {
        _queuedSave.value = queued
    }

    suspend fun onClipSaved(filePath: String) {
        _clipSavedEvent.emit(filePath)
    }
}
