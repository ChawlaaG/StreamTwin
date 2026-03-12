package com.streamtwin.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamtwin.data.local.StreamDataStore
import com.streamtwin.domain.model.TwitchUser
import com.streamtwin.domain.usecase.GetStreamKeyUseCase
import com.streamtwin.domain.usecase.GetTwitchUserUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val getTwitchUserUseCase: GetTwitchUserUseCase,
    private val getStreamKeyUseCase: GetStreamKeyUseCase,
    private val streamDataStore: StreamDataStore
) : ViewModel() {

    // UI States
    val streamTitle: StateFlow<String> = streamDataStore.streamTitleFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "My Awesome Stream")

    val streamQuality: StateFlow<String> = streamDataStore.streamQualityFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "1080p")

    val streamFps: StateFlow<Int> = streamDataStore.streamFpsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 60)

    val streamBitrate: StateFlow<Int> = streamDataStore.streamBitrateFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 6000 * 1024)

    private val _twitchUser = kotlinx.coroutines.flow.MutableStateFlow<TwitchUser?>(null)
    val twitchUser: StateFlow<TwitchUser?> = _twitchUser

    init {
        fetchUser()
    }

    private fun fetchUser() {
        viewModelScope.launch {
            val result = getTwitchUserUseCase()
            if (result.isSuccess) {
                val user = result.getOrNull()
                _twitchUser.value = user
                
                // Fetch stream key once user is known
                user?.let {
                    getStreamKeyUseCase(it.id)
                }
            }
        }
    }

    fun updateStreamTitle(title: String) {
        viewModelScope.launch {
            streamDataStore.saveStreamTitle(title)
        }
    }

    fun updateStreamQuality(quality: String) {
        viewModelScope.launch {
            streamDataStore.saveStreamQuality(quality)
        }
    }

    fun updateStreamFps(fps: Int) {
        viewModelScope.launch {
            streamDataStore.saveStreamFps(fps)
        }
    }

    fun updateStreamBitrate(bitrate: Int) {
        viewModelScope.launch {
            streamDataStore.saveStreamBitrate(bitrate)
        }
    }
}
