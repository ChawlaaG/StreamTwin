package com.streamtwin.domain.usecase

import com.streamtwin.data.repository.TwitchRepository
import com.streamtwin.domain.model.TwitchUser
import javax.inject.Inject

class GetTwitchUserUseCase @Inject constructor(
    private val repository: TwitchRepository
) {
    suspend operator fun invoke(): Result<TwitchUser> {
        return repository.getCurrentUser()
    }
}
