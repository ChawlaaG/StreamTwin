package com.streamtwin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamtwin.domain.usecase.AuthenticateTwitchUseCase
import com.streamtwin.domain.usecase.GetStreamKeyUseCase
import com.streamtwin.domain.usecase.GetTwitchUserUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.streamtwin.data.local.SecureStorageManager
import com.streamtwin.data.local.StreamDataStore
import com.streamtwin.data.local.StreamTwinSecurePrefs

@HiltViewModel
class MainViewModel @Inject constructor(
    private val authenticateTwitchUseCase: AuthenticateTwitchUseCase,
    private val getTwitchUserUseCase: GetTwitchUserUseCase,
    private val getStreamKeyUseCase: GetStreamKeyUseCase,
    private val secureStorageManager: SecureStorageManager,
    private val streamDataStore: StreamDataStore,
    private val securePrefs: StreamTwinSecurePrefs
) : ViewModel() {

    private val _authSuccess = MutableSharedFlow<Boolean>(replay = 1)
    val authSuccess: SharedFlow<Boolean> = _authSuccess

    private val _startModeRequests = MutableSharedFlow<String>(replay = 1)
    val startModeRequests: SharedFlow<String> = _startModeRequests

    val isTwitchConnected = streamDataStore.twitchEnabledFlow
    val isYouTubeConnected = streamDataStore.youtubeEnabledFlow
    val isKickConnected = streamDataStore.kickEnabledFlow

    fun handleYouTubeAuthToken(token: String) {
        viewModelScope.launch {
            secureStorageManager.youtubeAccessToken = token
            // Auto-enable YouTube since user just signed in with Google
            streamDataStore.saveYoutubeEnabled(true)
            // If Twitch isn't already authenticated, disable it so it doesn't
            // appear as an enabled-but-unconfigured destination
            val hasTwitchToken = !securePrefs.getAccessToken().isNullOrEmpty()
            if (!hasTwitchToken) {
                streamDataStore.saveTwitchEnabled(false)
            }
            _authSuccess.emit(true)
        }
    }

    fun handleTwitchAuthToken(token: String) {
        viewModelScope.launch {
            try {
                // 1. Save Token
                authenticateTwitchUseCase(token)
                
                // Auto-enable Twitch since user just authenticated
                streamDataStore.saveTwitchEnabled(true)
                
                // Emit success immediately so user moves to Home Screen
                _authSuccess.emit(true)
                
                // Optional: Trigger background pre-fetch without blocking navigation
                launch {
                    try {
                        val userResult = getTwitchUserUseCase()
                        val user = userResult.getOrThrow()
                        getStreamKeyUseCase(user.id).getOrThrow()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    fun markLoginSkipped() {
        viewModelScope.launch {
            streamDataStore.saveHasSkippedLogin(true)
        }
    }

    fun requestStartMode(mode: String) {
        _startModeRequests.tryEmit(mode)
    }
}
