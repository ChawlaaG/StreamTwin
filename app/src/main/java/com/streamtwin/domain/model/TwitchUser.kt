package com.streamtwin.domain.model

data class TwitchUser(
    val id: String,
    val login: String,
    val displayName: String,
    val profileImageUrl: String
)
