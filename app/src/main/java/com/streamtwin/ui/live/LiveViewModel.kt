package com.streamtwin.ui.live

import androidx.lifecycle.ViewModel
import com.streamtwin.data.repository.TwitchRepository
import com.streamtwin.domain.model.TwitchUser
import com.streamtwin.domain.usecase.GetStreamKeyUseCase
import com.streamtwin.domain.usecase.GetTwitchUserUseCase
import com.streamtwin.service.StreamStateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

@HiltViewModel
class LiveViewModel @Inject constructor(
    private val getTwitchUserUseCase: GetTwitchUserUseCase,
    private val getStreamKeyUseCase: GetStreamKeyUseCase,
    private val repository: TwitchRepository,
    private val streamDataStore: com.streamtwin.data.local.StreamDataStore
) : ViewModel() {

    val isLive = StreamStateManager.isLive
    val currentBitrate = StreamStateManager.currentBitrate
    val streamDuration = StreamStateManager.streamDuration

    private val _twitchUser = MutableStateFlow<TwitchUser?>(null)
    val twitchUser: StateFlow<TwitchUser?> = _twitchUser
    
    val streamKey: String? get() = repository.getStreamKey()

    val streamTitle = StreamStateManager.streamTitle
    
    val saveVodLocally = streamDataStore.saveVodLocallyFlow

    init {
        viewModelScope.launch {
            val user = getTwitchUserUseCase().getOrNull()
            _twitchUser.value = user
            
            // If stream key is missing, fetch it now
            if (repository.getStreamKey() == null) {
                user?.let { getStreamKeyUseCase(it.id) }
            }
        }
    }

    fun updateStreamMetadata(newTitle: String) {
        viewModelScope.launch {
            val user = _twitchUser.value
            if (user != null) {
                val result = repository.updateStreamMetadata(user.id, newTitle, null)
                if (result.isSuccess) {
                    StreamStateManager.saveStreamTitle(newTitle)
                }
            } else {
                StreamStateManager.saveStreamTitle(newTitle)
            }
        }
    }

    fun setSaveVodLocally(save: Boolean) {
        viewModelScope.launch {
            streamDataStore.saveVodLocally(save)
        }
    }
}
