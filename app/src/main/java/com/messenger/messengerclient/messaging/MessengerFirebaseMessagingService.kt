package com.messenger.messengerclient.messaging

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.messenger.messengerclient.MainActivity
import com.messenger.messengerclient.R
import com.messenger.messengerclient.ui.ChatActivity
import com.messenger.messengerclient.utils.ActivityCounter
import com.messenger.messengerclient.utils.PrefsManager

class MessengerFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        Log.d("FCM", "Message data: ${message.data}")

        val sender = message.data["sender"]
        val text = message.data["message"]
        val senderUsername = message.data["senderUsername"]
        val deepLinkAction = message.data["deepLinkAction"]
        val targetUsername = message.data["targetUsername"]

        if (sender != null && text != null) {
            if (deepLinkAction == "OPEN_CHAT" && targetUsername != null) {
                handleNewMessage(sender, text, senderUsername, targetUsername)
            }
        }
    }

    private fun handleNewMessage(sender: String, text: String, senderUsername: String?, targetUsername: String) {
        val currentUser = PrefsManager(this).username

        // 1. Не от себя
        if (senderUsername == currentUser) {
            Log.d("FCM", "Message from self - ignoring")
            return
        }

        // 2. Не если уже в чате с отправителем
        if (ActivityCounter.isChatWithUserOpen(senderUsername)) {
            Log.d("FCM", "Chat with sender already open - NO NOTIFICATION")
            return
        }

        // 3. Показываем уведомление
        showNotification(sender, text, targetUsername)
    }

    private fun showNotification(sender: String, text: String, targetUsername: String) {
        // Простой Intent для ChatActivity
        val chatIntent = Intent(this, ChatActivity::class.java).apply {
            putExtra("RECEIVER_USERNAME", targetUsername)
            putExtra("RECEIVER_DISPLAY_NAME", sender)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            chatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Создание уведомления
        createNotificationChannel()

        val notificationBuilder = NotificationCompat.Builder(this, "messenger_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(sender)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(System.currentTimeMillis().toInt(), notificationBuilder.build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "messenger_channel",
                "Messenger Notifications",
                NotificationManager.IMPORTANCE_HIGH
            )
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onNewToken(token: String) {
        Log.d("FCM", "New token: $token")
    }
}