package com.streamtwin.ui.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamtwin.data.local.SecureStorageManager
import com.streamtwin.data.local.StreamDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SplashViewModel @Inject constructor(
    secureStorageManager: SecureStorageManager,
    private val streamDataStore: StreamDataStore
) : ViewModel() {
    val shouldSkipConnect: StateFlow<Boolean?> = streamDataStore.hasSkippedLoginFlow
        .map { hasSkipped ->
            hasSkipped || secureStorageManager.youtubeAccessToken.isNotEmpty()
        }
        .stateIn(
            scope = viewModelScope,
            started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )
}
