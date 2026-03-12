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

@HiltViewModel
class MainViewModel @Inject constructor(
    private val authenticateTwitchUseCase: AuthenticateTwitchUseCase,
    private val getTwitchUserUseCase: GetTwitchUserUseCase,
    private val getStreamKeyUseCase: GetStreamKeyUseCase
) : ViewModel() {

    private val _authSuccess = MutableSharedFlow<Boolean>()
    val authSuccess: SharedFlow<Boolean> = _authSuccess

    fun handleTwitchAuthToken(token: String) {
        viewModelScope.launch {
            try {
                // 1. Save Token
                authenticateTwitchUseCase(token)
                
                // 2. Fetch User
                val userResult = getTwitchUserUseCase()
                val user = userResult.getOrThrow()
                
                // 3. Fetch Stream Key
                val keyResult = getStreamKeyUseCase(user.id)
                keyResult.getOrThrow() // Throw if fetching the stream key failed
                
                // Emit success only if stream key was successfully saved
                _authSuccess.emit(true)
            } catch (e: Exception) {
                e.printStackTrace()
                // Do not emit success, so user stays on connect screen 
                // In a production app, we would emit a UI error state here
            }
        }
    }
}
