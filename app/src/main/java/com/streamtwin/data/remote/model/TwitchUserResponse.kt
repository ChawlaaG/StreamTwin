package com.streamtwin.data.remote.model

import com.google.gson.annotations.SerializedName

data class TwitchUserResponse(
    @SerializedName("data") val data: List<TwitchUserData>
)

data class TwitchUserData(
    @SerializedName("id") val id: String,
    @SerializedName("login") val login: String,
    @SerializedName("display_name") val displayName: String,
    @SerializedName("profile_image_url") val profileImageUrl: String
)
