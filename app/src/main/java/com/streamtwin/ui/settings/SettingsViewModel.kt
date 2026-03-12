package com.streamtwin.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamtwin.data.repository.TwitchRepository
import com.streamtwin.domain.model.TwitchUser
import com.streamtwin.domain.usecase.GetTwitchUserUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: TwitchRepository,
    private val getTwitchUserUseCase: GetTwitchUserUseCase
) : ViewModel() {

    private val _twitchUser = MutableStateFlow<TwitchUser?>(null)
    val twitchUser: StateFlow<TwitchUser?> = _twitchUser

    init {
        viewModelScope.launch {
            _twitchUser.value = getTwitchUserUseCase().getOrNull()
        }
    }

    fun disconnect() {
        repository.logout()
    }
}
