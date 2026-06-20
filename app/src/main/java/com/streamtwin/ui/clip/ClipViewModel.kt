package com.streamtwin.ui.clip

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamtwin.data.clip.ClipRepository
import com.streamtwin.data.local.StreamDataStore
import com.streamtwin.service.ClipModeService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ClipViewModel @Inject constructor(
    private val clipRepository: ClipRepository,
    private val streamDataStore: StreamDataStore
) : ViewModel() {

    val isClipModeActive: StateFlow<Boolean> = clipRepository.isClipModeActive
    val clipSavedEvent: SharedFlow<String> = clipRepository.clipSavedEvent

    val clipDuration: StateFlow<Int> = streamDataStore.clipDurationFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, 60)

    val includeMic: StateFlow<Boolean> = streamDataStore.clipIncludeMicFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val clipOrientation: StateFlow<String> = streamDataStore.clipOrientationFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val backTapEnabled: StateFlow<Boolean> = streamDataStore.backTapEnabledFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun updateClipDuration(seconds: Int) {
        viewModelScope.launch {
            streamDataStore.saveClipDuration(seconds)
        }
    }

    fun updateIncludeMic(include: Boolean) {
        viewModelScope.launch {
            streamDataStore.saveClipIncludeMic(include)
        }
    }

    fun updateClipOrientation(orientation: String) {
        viewModelScope.launch {
            streamDataStore.saveClipOrientation(orientation)
        }
    }

    fun updateBackTapEnabled(enabled: Boolean) {
        viewModelScope.launch {
            streamDataStore.saveBackTapEnabled(enabled)
        }
    }

    fun setClipModeActive(active: Boolean) {
        clipRepository.setClipModeActive(active)
    }

    fun startClipMode(context: Context, resultCode: Int, projectionData: Intent) {
        val intent = Intent(context, ClipModeService::class.java).apply {
            action = ClipModeService.ACTION_START
            putExtra(ClipModeService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ClipModeService.EXTRA_PROJECTION_DATA, projectionData)
            putExtra(ClipModeService.EXTRA_CLIP_DURATION, clipDuration.value)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        clipRepository.setClipModeActive(true)
    }

    fun stopClipMode(context: Context) {
        val intent = Intent(context, ClipModeService::class.java).apply {
            action = ClipModeService.ACTION_STOP
        }
        context.startService(intent)
        clipRepository.setClipModeActive(false)
    }

    fun triggerClip(context: Context) {
        val intent = Intent(context, ClipModeService::class.java).apply {
            action = ClipModeService.ACTION_SAVE_CLIP
        }
        context.startService(intent)
    }
}
