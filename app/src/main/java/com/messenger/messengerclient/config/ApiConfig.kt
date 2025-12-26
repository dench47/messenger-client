package com.messenger.messengerclient.config

object ApiConfig {
    // ✅ ИСПРАВЛЕННЫЕ URL (БЕЗ ПОРТА 8080, С HTTPS/WSS)
    const val BASE_URL = "https://palomica.ru"
    const val WS_BASE_URL = "wss://palomica.ru/ws"

    // Endpoints (оставляем как есть)
    const val LOGIN_ENDPOINT = "/api/auth/login"
    const val REFRESH_TOKEN_ENDPOINT = "/api/auth/refresh"
    const val LOGOUT_ENDPOINT = "/api/auth/logout"
    const val USERS_ENDPOINT = "/api/users"
    const val MESSAGES_ENDPOINT = "/api/messages"
}