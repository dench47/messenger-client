package com.messenger.messengerclient.network.service

import com.messenger.messengerclient.data.model.AuthRequest
import com.messenger.messengerclient.data.model.AuthResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface AuthService {

    @POST("/api/auth/login")
    suspend fun login(@Body authRequest: AuthRequest): Response<AuthResponse>

    @POST("/api/auth/refresh")
    suspend fun refreshToken(@Body request: Map<String, String>): Response<AuthResponse>

    @POST("/api/auth/logout")
    suspend fun logout(@Body request: Map<String, String>): Response<Void>
}