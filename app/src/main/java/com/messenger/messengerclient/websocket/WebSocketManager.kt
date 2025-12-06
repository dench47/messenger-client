package com.messenger.messengerclient.websocket

import android.content.Context
import com.messenger.messengerclient.utils.PrefsManager

object WebSocketManager {

    fun initialize(context: Context): WebSocketService {
        // Всегда возвращаем Singleton instance
        return WebSocketService.getInstance()
    }

    fun getService(): WebSocketService? {
        return WebSocketService.getInstance()
    }

    fun connectIfNeeded(context: Context) {
        val prefsManager = PrefsManager(context)
        val token = prefsManager.authToken
        val username = prefsManager.username

        if (!token.isNullOrEmpty() && !username.isNullOrEmpty()) {
            val service = getService()
            if (service != null && !service.isConnected()) {
                service.connect(token, username)
            }
        }
    }

    fun disconnect() {
        WebSocketService.getInstance().disconnect()
    }
}