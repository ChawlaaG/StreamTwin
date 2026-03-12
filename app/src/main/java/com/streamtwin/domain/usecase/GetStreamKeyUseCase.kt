package com.streamtwin.domain.usecase

import com.streamtwin.data.repository.TwitchRepository
import javax.inject.Inject

class GetStreamKeyUseCase @Inject constructor(
    private val repository: TwitchRepository
) {
    suspend operator fun invoke(userId: String): Result<String> {
        return repository.fetchAndSaveStreamKey(userId)
    }
}
