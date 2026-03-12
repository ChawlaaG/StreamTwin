package com.streamtwin.data.remote.model

import com.google.gson.annotations.SerializedName

data class TwitchStreamKeyResponse(
    @SerializedName("data") val data: List<TwitchStreamKeyData>
)

data class TwitchStreamKeyData(
    @SerializedName("stream_key") val streamKey: String
)
