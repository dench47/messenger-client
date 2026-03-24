package com.messenger.messengerclient.messaging

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.messenger.messengerclient.data.local.AppDatabase
import com.messenger.messengerclient.data.model.Message
import com.messenger.messengerclient.data.model.StatusUpdateRequest
import com.messenger.messengerclient.network.RetrofitClient
import com.messenger.messengerclient.service.MessageService
import com.messenger.messengerclient.service.MessengerService
import com.messenger.messengerclient.service.UserService
import com.messenger.messengerclient.ui.CallActivity
import com.messenger.messengerclient.ui.ChatActivity
import com.messenger.messengerclient.utils.ActivityCounter
import com.messenger.messengerclient.utils.PrefsManager
import com.messenger.messengerclient.utils.toLocal
import com.messenger.messengerclient.websocket.WebSocketManager
import com.messenger.messengerclient.websocket.WebSocketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MessengerFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private val pendingMessagesMap = mutableMapOf<String, MutableList<String>>()

        fun clearPendingMessages(sender: String) {
            pendingMessagesMap.remove(sender)
        }

        fun cancelNotification(sender: String, context: Context) {
            val groupKey = "messenger_group_$sender"
            val summaryId = groupKey.hashCode()
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(summaryId)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val type = message.data["type"]
        val action = message.data["action"]
        val sender = message.data["sender"]
        val text = message.data["message"]
        val senderUsername = message.data["senderUsername"]
        val callerUsername = message.data["callerUsername"]
        val targetUsername = message.data["targetUsername"]
        val deepLinkAction = message.data["deepLinkAction"]
        val callType = message.data["callType"]

        val messageId = message.data["messageId"]?.toLongOrNull()
        val content = message.data["content"] ?: text ?: ""
        val timestamp = message.data["timestamp"] ?: ""
        val status = message.data["status"]

        if (type == "SERVER_RESTARTED" && action == "DO_BACKGROUND") {
            handleServerRestart()
            return
        }

        when (type) {
            "INCOMING_CALL" -> {
                handleIncomingCall(
                    callerUsername ?: sender ?: "Unknown",
                    targetUsername ?: "",
                    callType ?: "audio"
                )
            }

            "NEW_MESSAGE" -> {
                if (messageId != null && senderUsername != null) {
                    saveMessageAndSendDelivered(
                        messageId = messageId,
                        senderUsername = senderUsername,
                        content = content,
                        timestamp = timestamp,
                        targetUsername = targetUsername ?: ""
                    )
                }

                if (deepLinkAction == "OPEN_CHAT" && targetUsername != null && senderUsername != null) {
                    handleNewMessage(
                        sender ?: "Unknown",
                        text ?: "",
                        senderUsername,
                        targetUsername
                    )
                }
            }

            "STATUS_UPDATE" -> {
                handleStatusUpdate(
                    messageId = messageId,
                    status = status,
                    senderUsername = senderUsername,
                    receiverUsername = targetUsername
                )
            }
        }
    }

    private fun handleStatusUpdate(
        messageId: Long?,
        status: String?,
        senderUsername: String?,
        receiverUsername: String?
    ) {
        if (messageId == null || status == null || senderUsername == null || receiverUsername == null) {
            return
        }

        val prefsManager = PrefsManager(this)
        val currentUser = prefsManager.username

        val updatedMessage = Message(
            id = messageId,
            content = "",
            senderUsername = senderUsername,
            receiverUsername = receiverUsername,
            timestamp = "",
            isRead = status == "READ",
            status = status
        )

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(this@MessengerFirebaseMessagingService)
                db.messageDao().updateMessageStatusAndRead(
                    messageId = messageId,
                    status = status,
                    isRead = status == "READ"
                )
            } catch (e: Exception) {
                Log.e("FCM", "Error updating message status: ${e.message}")
            }
        }

        Handler(Looper.getMainLooper()).post {
            WebSocketService.getInstance().notifyStatusListeners(updatedMessage)
        }
    }

    private fun saveMessageAndSendDelivered(

        messageId: Long,
        senderUsername: String,
        content: String,
        timestamp: String,
        targetUsername: String
    ) {
        Log.d("FCM", "📦📦📦 saveMessageAndSendDelivered CALLED for message $messageId")

        val prefsManager = PrefsManager(this)
        val currentUser = prefsManager.username

        if (currentUser.isNullOrEmpty()) {
            return
        }

        if (targetUsername != currentUser) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(this@MessengerFirebaseMessagingService)
                val existingMessage = db.messageDao().getMessageById(messageId)

                if (existingMessage != null) {
                    if (existingMessage.status != "DELIVERED" && existingMessage.status != "READ") {
                        sendDeliveredConfirmation(messageId, senderUsername)
                    }
                    return@launch
                }

                val message = Message(
                    id = messageId,
                    content = content,
                    senderUsername = senderUsername,
                    receiverUsername = currentUser,
                    timestamp = timestamp,
                    isRead = false,
                    status = "SENT"
                )

                db.messageDao().insertMessage(message.toLocal())
                sendDeliveredConfirmation(messageId, senderUsername)

            } catch (e: Exception) {
                Log.e("FCM", "Error saving message: ${e.message}")
            }
        }
    }

    private fun sendDeliveredConfirmation(messageId: Long, senderUsername: String) {
        val prefsManager = PrefsManager(this)
        val currentUser = prefsManager.username

        if (currentUser.isNullOrEmpty()) {
            return
        }

        if (ActivityCounter.isChatWithUserOpen(senderUsername)) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(this@MessengerFirebaseMessagingService)
                val existingMessage = db.messageDao().getMessageById(messageId)

                if (existingMessage?.status == "READ" || existingMessage?.status == "DELIVERED") {
                    return@launch
                }

                db.messageDao().updateMessageStatusAndRead(
                    messageId = messageId,
                    status = "DELIVERED",
                    isRead = false
                )

                Handler(Looper.getMainLooper()).post {
                    try {
                        val webSocketService = WebSocketManager.getService()
                        if (webSocketService != null && webSocketService.isConnected()) {
                            webSocketService.sendStatusConfirmation(
                                messageId,
                                "DELIVERED",
                                currentUser
                            )
                        } else {
                            sendDeliveredViaHttp(messageId, senderUsername, currentUser)
                        }
                    } catch (e: Exception) {
                        sendDeliveredViaHttp(messageId, senderUsername, currentUser)
                    }
                }
            } catch (e: Exception) {
                Log.e("FCM", "Error: ${e.message}")
            }
        }
    }

    private fun sendDeliveredViaHttp(messageId: Long, senderUsername: String, currentUser: String) {
        Log.d("FCM", "🌐🌐🌐 sendDeliveredViaHttp CALLED for message $messageId")

        CoroutineScope(Dispatchers.IO).launch {

            // 👇 ЖДЕМ 3 СЕКУНДЫ, ЧТОБЫ СЕТЬ СТАБИЛИЗИРОВАЛАСЬ
            delay(1000)

            try {
                val prefsManager = PrefsManager(this@MessengerFirebaseMessagingService)
                val token = prefsManager.authToken
                Log.d("FCM", "🌐 Token: ${token?.take(20)}...")

                if (token.isNullOrEmpty()) {
                    Log.e("FCM", "❌ No auth token")
                    return@launch
                }

                val statusUpdate = StatusUpdateRequest(
                    messageId = messageId,
                    status = "DELIVERED",
                    username = currentUser
                )

                Log.d("FCM", "🌐 Sending HTTP request for message $messageId")

                val client = RetrofitClient.getClientWithAuth(token)
                val messageService = client.create(MessageService::class.java)
                val response = messageService.updateMessageStatus(statusUpdate)

                Log.d("FCM", "🌐 Response code: ${response.code()}")

                if (response.isSuccessful) {
                    Log.d("FCM", "✅ DELIVERED sent via HTTP for message $messageId")
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e("FCM", "❌ HTTP failed: ${response.code()} - $errorBody")
                }
            } catch (e: Exception) {
                Log.e("FCM", "❌ HTTP error: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun handleIncomingCall(caller: String, targetUsername: String, callType: String) {
        val prefsManager = PrefsManager(this)
        val currentUser = prefsManager.username

        if (currentUser != targetUsername || ActivityCounter.isInCall()) {
            return
        }

        val callIntent = Intent(this, CallActivity::class.java).apply {
            putExtra(CallActivity.EXTRA_CALL_TYPE, callType)
            putExtra(CallActivity.EXTRA_TARGET_USER, caller)
            putExtra(CallActivity.EXTRA_IS_INCOMING, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            callIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        createNotificationChannel()

        val notificationBuilder = NotificationCompat.Builder(this, "messenger_calls")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("📞 Входящий звонок")
            .setContentText("$caller звонит вам")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setFullScreenIntent(pendingIntent, true)
            .setTimeoutAfter(30000)

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1001, notificationBuilder.build())
        startActivity(callIntent)
    }

    private fun handleNewMessage(
        sender: String,
        text: String,
        senderUsername: String,
        targetUsername: String
    ) {
        val currentUser = PrefsManager(this).username

        if (senderUsername == currentUser || ActivityCounter.isChatWithUserOpen(senderUsername)) {
            return
        }

        showMessageNotification(sender, text, senderUsername, targetUsername)
    }

    private fun showMessageNotification(
        sender: String,
        text: String,
        senderUsername: String,
        targetUsername: String
    ) {

        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("RECEIVER_USERNAME", senderUsername)
            putExtra("RECEIVER_DISPLAY_NAME", sender)
            putExtra("TARGET_USERNAME", targetUsername)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        createNotificationChannel()

        val groupKey = "messenger_group_$senderUsername"
        val summaryId = groupKey.hashCode()

        // Получаем накопленные сообщения для этого отправителя
        val messages = pendingMessagesMap.getOrPut(senderUsername) { mutableListOf() }
        messages.add(text)

        // Создаем сводное уведомление с InboxStyle
        val inboxStyle = NotificationCompat.InboxStyle()
        messages.forEach { msg ->
            inboxStyle.addLine(msg)
        }
        inboxStyle.setSummaryText("${messages.size} сообщений")

        val summaryBuilder = NotificationCompat.Builder(this, "messenger_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(sender)
            .setContentText(if (messages.size == 1) text else "${messages.size} новых сообщений")
            .setStyle(inboxStyle)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setGroup(groupKey)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(summaryId, summaryBuilder.build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val messageChannel = NotificationChannel(
                "messenger_channel",
                "Сообщения",
                NotificationManager.IMPORTANCE_HIGH
            )

            val callChannel = NotificationChannel(
                "messenger_calls",
                "Звонки",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о входящих звонках"
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 500, 1000)
            }

            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(messageChannel)
            notificationManager.createNotificationChannel(callChannel)
        }
    }

    override fun onNewToken(token: String) {
        val prefsManager = PrefsManager(this)
        val currentUser = prefsManager.username

        if (!currentUser.isNullOrEmpty()) {
            sendFcmTokenToServer(currentUser, token)
        }
    }

    private fun sendFcmTokenToServer(username: String, fcmToken: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val userService = RetrofitClient.getClient().create(UserService::class.java)
                val request = mapOf("username" to username, "fcmToken" to fcmToken)
                userService.updateFcmToken(request)
            } catch (e: Exception) {
                Log.e("FCM", "Error sending FCM token: ${e.message}")
            }
        }
    }

    private fun handleServerRestart() {
        val prefsManager = PrefsManager(this)
        val currentUser = prefsManager.username

        if (currentUser.isNullOrEmpty()) {
            return
        }

        val bgIntent = Intent(this, MessengerService::class.java)
        bgIntent.action = MessengerService.ACTION_APP_BACKGROUND
        startService(bgIntent)

        Handler(Looper.getMainLooper()).postDelayed({
            val fgIntent = Intent(this, MessengerService::class.java)
            fgIntent.action = MessengerService.ACTION_APP_FOREGROUND
            startService(fgIntent)
        }, 5000)
    }
}