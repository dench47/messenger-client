package com.messenger.messengerclient.network.service

import com.messenger.messengerclient.data.model.User
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface UserService {

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

    // ДОБАВЬТЕ ЭТОТ МЕТОД - он отсутствовал!
    @POST("/api/auth/logout")
    suspend fun logout(@Body request: Map<String, String?>): Response<Void>
}