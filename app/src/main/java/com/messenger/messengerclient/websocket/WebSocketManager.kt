package com.messenger.messengerclient.websocket

import android.content.Context
import com.messenger.messengerclient.utils.PrefsManager

object WebSocketManager {

    private var webSocketService: WebSocketService? = null

    fun initialize(context: Context): WebSocketService {
        if (webSocketService == null) {
            webSocketService = WebSocketService()
        }
        return webSocketService!!
    }

    fun connectIfNeeded(context: Context) {
        val prefsManager = PrefsManager(context)
        val token = prefsManager.authToken
        val username = prefsManager.username

        if (!token.isNullOrEmpty() && !username.isNullOrEmpty()) {
            val service = initialize(context)
            if (!service.isConnected()) {
                service.connect(token, username)
            }
        }
    }

    fun getService(): WebSocketService? {
        return webSocketService
    }

    fun disconnect() {
        webSocketService?.disconnect()
        webSocketService = null
    }
}