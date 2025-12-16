package com.messenger.messengerclient.service

import com.messenger.messengerclient.data.model.User
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface UserService {

    data class UpdateOnlineStatusRequest(
        val username: String,
        val online: Boolean
    )

    @GET("/api/users")
    suspend fun getUsers(): Response<List<User>>

    @GET("/api/users/search")
    suspend fun searchUsers(@Query("query") query: String): Response<List<User>>

    @GET("/api/users/{username}")
    suspend fun getUser(@Path("username") username: String): Response<User>

    @GET("/api/users/online")
    suspend fun getOnlineUsers(): Response<List<String>>

    @GET("/api/users/{username}/online")
    suspend fun isUserOnline(@Path("username") username: String): Response<Boolean>
    @POST("/api/auth/logout")
    suspend fun logout(@Body request: Map<String, String?>): Response<Void>

    @POST("/api/users/{username}/update-last-seen")
    suspend fun updateLastSeen(@Path("username") username: String): Response<Void>

    @GET("/api/users/{username}/last-seen")
    suspend fun getLastSeen(@Path("username") username: String): Response<String>

    @POST("/api/users/update-online-status")
    suspend fun updateOnlineStatus(@Body request: UpdateOnlineStatusRequest): Response<Void>
    @POST("/api/users/update-activity")
    suspend fun updateActivity(@Body request: Map<String, String>): Response<Void>
    @POST("/api/users/update-fcm-token")
    suspend fun updateFcmToken(@Body request: Map<String, String>): Response<Void>
}