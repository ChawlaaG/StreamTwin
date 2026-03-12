package com.streamtwin.domain.usecase

import com.streamtwin.data.repository.TwitchRepository
import javax.inject.Inject

class AuthenticateTwitchUseCase @Inject constructor(
    private val repository: TwitchRepository
) {
    operator fun invoke(accessToken: String) {
        repository.saveAccessToken(accessToken)
    }
}
