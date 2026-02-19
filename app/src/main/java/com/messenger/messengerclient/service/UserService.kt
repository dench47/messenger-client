package com.messenger.messengerclient.service

import com.messenger.messengerclient.data.model.ContactDto
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

    @POST("/api/auth/logout")
    suspend fun logout(@Body request: Map<String, String?>): Response<Void>

    @POST("/api/users/{username}/update-last-seen")
    suspend fun updateLastSeen(@Path("username") username: String): Response<Void>

    @POST("/api/users/update-fcm-token")
    suspend fun updateFcmToken(@Body request: Map<String, String>): Response<Void>

    @POST("/api/auth/remove-fcm-token")
    suspend fun removeFcmToken(@Body request: Map<String, String>): Response<Void>

    @GET("/api/users/contacts")
    suspend fun getContacts(@Query("username") username: String): Response<List<ContactDto>>

    @POST("/api/contacts/add")
    suspend fun addContact(@Body request: Map<String, String?>): Response<Void>

    @GET("/api/users/{username}/status")
    suspend fun getUserStatus(@Path("username") username: String): Response<ContactDto>
}