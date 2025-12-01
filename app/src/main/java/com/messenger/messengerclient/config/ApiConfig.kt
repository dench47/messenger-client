package com.messenger.messengerclient.config

object ApiConfig {
    const val BASE_URL = "http://mimuserver.servequake.com:8080"
    const val WS_BASE_URL = "ws://mimuserver.servequake.com:8080/ws"

    // Endpoints
    const val LOGIN_ENDPOINT = "/api/auth/login"
    const val REFRESH_TOKEN_ENDPOINT = "/api/auth/refresh"
    const val LOGOUT_ENDPOINT = "/api/auth/logout"
    const val USERS_ENDPOINT = "/api/users"
    const val MESSAGES_ENDPOINT = "/api/messages"
}