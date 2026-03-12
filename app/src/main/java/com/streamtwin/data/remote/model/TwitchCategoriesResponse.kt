package com.streamtwin.data.remote.model

import com.google.gson.annotations.SerializedName

data class TwitchCategoriesResponse(
    @SerializedName("data") val data: List<TwitchCategory>
)

data class TwitchCategory(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("box_art_url") val boxArtUrl: String
)
