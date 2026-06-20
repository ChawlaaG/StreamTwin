package com.streamtwin.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamtwin.data.local.SecureStorageManager
import com.streamtwin.data.local.StreamDataStore
import com.streamtwin.domain.model.TwitchUser
import com.streamtwin.domain.usecase.GetStreamKeyUseCase
import com.streamtwin.domain.usecase.GetTwitchUserUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val getTwitchUserUseCase: com.streamtwin.domain.usecase.GetTwitchUserUseCase,
    private val getStreamKeyUseCase: com.streamtwin.domain.usecase.GetStreamKeyUseCase,
    private val streamDataStore: com.streamtwin.data.local.StreamDataStore,
    private val twitchRepository: com.streamtwin.data.repository.TwitchRepository,
    private val secureStorageManager: SecureStorageManager
) : ViewModel() {

    private val TAG = "HomeViewModel"

    // UI States
    val streamTitle: StateFlow<String> = streamDataStore.streamTitleFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "My Awesome Stream")

    val streamQuality: StateFlow<String> = streamDataStore.streamQualityFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "1080p")

    val streamFps: StateFlow<Int> = streamDataStore.streamFpsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 60)

    val streamBitrate: StateFlow<Int> = streamDataStore.streamBitrateFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 6000 * 1024)

    val selectedGameId: StateFlow<String> = streamDataStore.selectedGameIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "509658")

    val selectedGameName: StateFlow<String> = streamDataStore.selectedGameNameFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Just Chatting")

    val saveVodLocally: StateFlow<Boolean> = streamDataStore.saveVodLocallyFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val aspectRatio: StateFlow<String> = streamDataStore.aspectRatioFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "landscape")

    private val _categories = MutableStateFlow<List<com.streamtwin.data.remote.model.TwitchCategory>>(emptyList())
    val categories: StateFlow<List<com.streamtwin.data.remote.model.TwitchCategory>> = _categories

    private val _twitchUser = kotlinx.coroutines.flow.MutableStateFlow<TwitchUser?>(null)
    val twitchUser: StateFlow<TwitchUser?> = _twitchUser

    private val _isStreamKeyReady = MutableStateFlow(false)
    val isStreamKeyReady: StateFlow<Boolean> = _isStreamKeyReady

    val streamHealth: StateFlow<com.streamtwin.service.StreamStateManager.StreamHealth> = com.streamtwin.service.StreamStateManager.streamHealth

    val twitchEnabled: StateFlow<Boolean> = streamDataStore.twitchEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val youtubeEnabled: StateFlow<Boolean> = streamDataStore.youtubeEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val kickEnabled: StateFlow<Boolean> = streamDataStore.kickEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _loadingError = MutableStateFlow<String?>(null)
    val loadingError: StateFlow<String?> = _loadingError

    private var _streamKey: String? = null
    val streamKey: String? get() = _streamKey

    init {
        fetchUser()
        fetchTopCategories()
    }

    /**
     * Checks if ANY enabled destination has its key configured.
     * This allows Go Live to work for YouTube-only or Kick-only modes.
     */
    private suspend fun refreshStreamKeyReadiness() {
        val twitchEnabled = streamDataStore.twitchEnabledFlow.first()
        val youtubeEnabled = streamDataStore.youtubeEnabledFlow.first()
        val kickEnabled = streamDataStore.kickEnabledFlow.first()

        val twitchReady = twitchEnabled && !_streamKey.isNullOrEmpty()
        val youtubeReady = youtubeEnabled && secureStorageManager.youtubeAccessToken.isNotEmpty()
        val kickReady = kickEnabled && secureStorageManager.kickStreamKey.isNotEmpty()
                && secureStorageManager.kickRtmpUrl.isNotEmpty()

        val anyReady = twitchReady || youtubeReady || kickReady
        Log.d(TAG, "refreshStreamKeyReadiness: twitch=$twitchReady, youtube=$youtubeReady, kick=$kickReady → anyReady=$anyReady")
        _isStreamKeyReady.value = anyReady
    }

    private fun fetchTopCategories() {
        viewModelScope.launch {
            val result = twitchRepository.getTopGames()
            if (result.isSuccess) {
                _categories.value = result.getOrNull() ?: emptyList()
            }
        }
    }

    fun searchCategories(query: String) {
        if (query.isBlank()) {
            fetchTopCategories()
            return
        }
        viewModelScope.launch {
            val result = twitchRepository.searchCategories(query)
            if (result.isSuccess) {
                _categories.value = result.getOrNull() ?: emptyList()
            }
        }
    }

    fun selectCategory(id: String, name: String) {
        viewModelScope.launch {
            streamDataStore.saveSelectedGame(id, name)
        }
    }

    fun setSaveVodLocally(save: Boolean) {
        viewModelScope.launch {
            streamDataStore.saveVodLocally(save)
        }
    }

    fun syncSettingsWithTwitch(title: String, gameId: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val twitchEnabled = streamDataStore.twitchEnabledFlow.first()
            if (!twitchEnabled) {
                // Skip Twitch API sync when Twitch is disabled (YouTube/Kick only)
                Log.d(TAG, "syncSettingsWithTwitch: Twitch disabled, skipping API sync")
                onComplete(true)
                return@launch
            }

            val user = _twitchUser.value ?: run {
                onComplete(false)
                return@launch
            }
            
            val result = twitchRepository.updateStreamMetadata(user.id, title, gameId)
            Log.d(TAG, "syncSettingsWithTwitch: title=$title, gameId=$gameId, success=${result.isSuccess}")
            onComplete(result.isSuccess)
        }
    }

    fun fetchUser() {
        _loadingError.value = null
        viewModelScope.launch {
            val twitchEnabled = streamDataStore.twitchEnabledFlow.first()

            if (twitchEnabled) {
                val result = getTwitchUserUseCase()
                if (result.isSuccess) {
                    val user = result.getOrNull()
                    _twitchUser.value = user

                    // Fetch stream key once user is known
                    user?.let {
                        val keyResult = getStreamKeyUseCase(it.id)
                        if (keyResult.isSuccess) {
                            _streamKey = keyResult.getOrNull()
                        } else {
                            Log.w(TAG, "Failed to fetch Twitch stream key: ${keyResult.exceptionOrNull()?.message}")
                        }
                    }
                } else {
                    Log.w(TAG, "Failed to fetch Twitch user: ${result.exceptionOrNull()?.message}")
                    // Don't block Go Live — YouTube/Kick might still be enabled
                }
            }

            // Check ALL destinations, not just Twitch
            refreshStreamKeyReadiness()

            // Only show a fatal error if NO destination is ready
            if (!_isStreamKeyReady.value) {
                _loadingError.value = if (twitchEnabled) {
                    "Failed to fetch Twitch stream key. Check connection."
                } else {
                    "No streaming destination is configured. Check Settings."
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

    fun updateAspectRatio(ratio: String) {
        viewModelScope.launch {
            streamDataStore.saveAspectRatio(ratio)
        }
    }
}
