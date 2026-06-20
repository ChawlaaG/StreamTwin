package com.streamtwin.data.remote

import com.streamtwin.data.remote.model.TwitchStreamKeyResponse
import com.streamtwin.data.remote.model.TwitchUserResponse
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

interface TwitchApiService {

    @GET("users")
    suspend fun getUsers(
        @Header("Authorization") authorization: String,
        @Header("Client-Id") clientId: String
    ): TwitchUserResponse

    @GET("streams/key")
    suspend fun getStreamKey(
        @Header("Authorization") authorization: String,
        @Header("Client-Id") clientId: String,
        @Query("broadcaster_id") broadcasterId: String
    ): TwitchStreamKeyResponse

    @retrofit2.http.PATCH("channels")
    suspend fun modifyChannelInformation(
        @Header("Authorization") authorization: String,
        @Header("Client-Id") clientId: String,
        @Query("broadcaster_id") broadcasterId: String,
        @retrofit2.http.Body body: Map<String, String>
    ): retrofit2.Response<Unit>

    @GET("games/top")
    suspend fun getTopGames(
        @Header("Authorization") authorization: String,
        @Header("Client-Id") clientId: String,
        @Query("first") first: Int = 20
    ): com.streamtwin.data.remote.model.TwitchCategoriesResponse

    @GET("search/categories")
    suspend fun searchCategories(
        @Header("Authorization") authorization: String,
        @Header("Client-Id") clientId: String,
        @Query("query") query: String
    ): com.streamtwin.data.remote.model.TwitchCategoriesResponse
}
