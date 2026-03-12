package com.streamtwin.ui.splash

import androidx.lifecycle.ViewModel
import com.streamtwin.data.repository.TwitchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SplashViewModel @Inject constructor(
    repository: TwitchRepository
) : ViewModel() {
    private val _hasToken = MutableStateFlow(repository.hasToken())
    val hasToken: StateFlow<Boolean> = _hasToken
}
