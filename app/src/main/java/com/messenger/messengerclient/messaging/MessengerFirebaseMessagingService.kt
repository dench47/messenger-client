package com.messenger.messengerclient.messaging

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MessengerFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Log.d("FCM", "New token: $token")
        // Сохранить токен на сервере
    }

    override fun onMessageReceived(message: RemoteMessage) {
        Log.d("FCM", "Message received: ${message.data}")

        // Обработать уведомление о новом сообщении
        val sender = message.data["sender"]
        val text = message.data["message"]

        if (sender != null && text != null) {
            showNotification(sender, text)
        }
    }

    private fun showNotification(sender: String, message: String) {
        // Показать уведомление
        // TODO: реализовать
    }
}