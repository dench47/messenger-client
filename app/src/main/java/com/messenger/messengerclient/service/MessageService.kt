package com.messenger.messengerclient.service

import com.messenger.messengerclient.data.model.Message
import retrofit2.Response
import retrofit2.http.*

interface MessageService {

    @POST("/api/messages/send")
    @Headers("Content-Type: application/json")
    suspend fun sendMessage(@Body message: Message): Response<Message>

    @GET("/api/messages/conversation")
    suspend fun getConversation(
        @Query("user1") user1: String,
        @Query("user2") user2: String
    ): Response<List<Message>>

    @POST("/api/messages/{messageId}/read")
    suspend fun markAsRead(@Path("messageId") messageId: Long): Response<Void>

    @GET("/api/messages/unread")
    suspend fun getUnreadMessages(@Query("username") username: String): Response<List<Message>>

    @GET("/api/messages/unread/count")
    suspend fun getUnreadCount(@Query("username") username: String): Response<Long>


}