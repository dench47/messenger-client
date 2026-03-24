package com.messenger.messengerclient.messaging

import android.annotation.SuppressLint
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
import androidx.core.content.edit
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
        private const val PREFS_NAME = "fcm_notification_prefs"
        private const val KEY_TOTAL_UNREAD = "total_unread_count"

        // Храним сообщения в памяти (только для текущей сессии)
        private val pendingMessagesMap = mutableMapOf<String, MutableList<String>>()

        fun clearPendingMessages(sender: String, context: Context) {
            pendingMessagesMap.remove(sender)
            updateTotalBadgeCount(context)
        }

        fun cancelNotification(sender: String, context: Context) {
            val groupKey = "messenger_group_$sender"
            val summaryId = groupKey.hashCode()
            val notificationManager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(summaryId)
            clearPendingMessages(sender, context)
        }

        // 👇 ТОЛЬКО ОБНОВЛЯЕМ СЧЕТЧИК В SHAREDPREFERENCES, НЕ ПОКАЗЫВАЕМ УВЕДОМЛЕНИЕ
        private fun updateTotalBadgeCount(context: Context) {
            val totalMessages = pendingMessagesMap.values.sumOf { it.size }
            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit { putInt(KEY_TOTAL_UNREAD, totalMessages) }

            // 👇 НЕ ПОКАЗЫВАЕМ УВЕДОМЛЕНИЕ - только обновляем счетчик в SharedPreferences
            // Бейдж на иконке обновится автоматически при следующем показе сводного уведомления
        }

    }

    override fun onMessageReceived(message: RemoteMessage) {
        Log.d("FCM", "Message received: ${message.data}")

        val data = message.data
        val type = data["type"]
        val action = data["action"]
        val sender = data["sender"]
        val text = data["message"]
        val senderUsername = data["senderUsername"]
        val callerUsername = data["callerUsername"]
        val targetUsername = data["targetUsername"]
        val deepLinkAction = data["deepLinkAction"]
        val callType = data["callType"]
        val messageId = data["messageId"]?.toLongOrNull()
        val content = data["content"] ?: text ?: ""
        val timestamp = data["timestamp"] ?: ""
        val status = data["status"]

        if (type == "SERVER_RESTARTED" && action == "DO_BACKGROUND") {
            handleServerRestart()
            return
        }

        when (type) {
            "INCOMING_CALL" -> handleIncomingCall(
                callerUsername ?: sender ?: "Unknown",
                targetUsername ?: "",
                callType ?: "audio"
            )
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
            "STATUS_UPDATE" -> handleStatusUpdate(
                messageId = messageId,
                status = status,
                senderUsername = senderUsername,
                receiverUsername = targetUsername
            )
        }
    }

    private fun handleStatusUpdate(
        messageId: Long?,
        status: String?,
        senderUsername: String?,
        receiverUsername: String?
    ) {
        if (messageId == null || status == null || senderUsername == null || receiverUsername == null) return

        PrefsManager(this).username ?: return

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
                Handler(Looper.getMainLooper()).post {
                    WebSocketService.getInstance().notifyStatusListeners(updatedMessage)
                }
            } catch (e: Exception) {
                Log.e("FCM", "Error updating message status: ${e.message}")
            }
        }
    }

    private fun saveMessageAndSendDelivered(
        messageId: Long,
        senderUsername: String,
        content: String,
        timestamp: String,
        targetUsername: String
    ) {
        Log.d("FCM", "📦 saveMessageAndSendDelivered for message $messageId")

        val prefsManager = PrefsManager(this)
        val currentUser = prefsManager.username ?: return

        if (targetUsername != currentUser) return

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
        val currentUser = prefsManager.username ?: return

        if (ActivityCounter.isChatWithUserOpen(senderUsername)) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(this@MessengerFirebaseMessagingService)
                val existingMessage = db.messageDao().getMessageById(messageId)

                if (existingMessage?.status == "READ" || existingMessage?.status == "DELIVERED") return@launch

                db.messageDao().updateMessageStatusAndRead(
                    messageId = messageId,
                    status = "DELIVERED",
                    isRead = false
                )

                val webSocketService = WebSocketManager.getService()
                if (webSocketService != null && webSocketService.isConnected()) {
                    Handler(Looper.getMainLooper()).post {
                        webSocketService.sendStatusConfirmation(messageId, "DELIVERED", currentUser)
                    }
                } else {
                    sendDeliveredViaHttpWithRetry(messageId, currentUser, 0)
                }
            } catch (e: Exception) {
                Log.e("FCM", "Error: ${e.message}")
                sendDeliveredViaHttpWithRetry(messageId, currentUser, 0)
            }
        }
    }

    private fun sendDeliveredViaHttpWithRetry(messageId: Long, currentUser: String, attempt: Int) {
        val maxRetries = 3
        val baseDelay = 2000L

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefsManager = PrefsManager(this@MessengerFirebaseMessagingService)
                val token = prefsManager.authToken

                if (token.isNullOrEmpty()) {
                    Log.e("FCM", "❌ No auth token")
                    if (attempt < maxRetries) {
                        delay(baseDelay * (attempt + 1))
                        sendDeliveredViaHttpWithRetry(messageId, currentUser, attempt + 1)
                    }
                    return@launch
                }

                val statusUpdate = StatusUpdateRequest(
                    messageId = messageId,
                    status = "DELIVERED",
                    username = currentUser
                )

                val client = RetrofitClient.getClientWithAuth(token)
                val messageService = client.create(MessageService::class.java)
                val response = messageService.updateMessageStatus(statusUpdate)

                if (response.isSuccessful) {
                    Log.d("FCM", "✅ DELIVERED sent via HTTP for message $messageId")
                } else {
                    Log.e("FCM", "❌ HTTP failed: ${response.code()}")
                    if (attempt < maxRetries) {
                        delay(baseDelay * (attempt + 1))
                        sendDeliveredViaHttpWithRetry(messageId, currentUser, attempt + 1)
                    }
                }
            } catch (e: Exception) {
                Log.e("FCM", "❌ HTTP error: ${e.message}")
                if (attempt < maxRetries) {
                    delay(baseDelay * (attempt + 1))
                    sendDeliveredViaHttpWithRetry(messageId, currentUser, attempt + 1)
                }
            }
        }
    }

    @SuppressLint("FullScreenIntentPolicy")
    private fun handleIncomingCall(caller: String, targetUsername: String, callType: String) {
        val prefsManager = PrefsManager(this)
        val currentUser = prefsManager.username

        if (currentUser != targetUsername || ActivityCounter.isInCall()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.e("FCM", "❌ No notification permission, cannot show incoming call")
                return
            }
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

        createNotificationChannels()

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

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
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
        if (senderUsername == currentUser || ActivityCounter.isChatWithUserOpen(senderUsername)) return
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

        createNotificationChannels()

        val groupKey = "messenger_group_$senderUsername"
        val summaryId = groupKey.hashCode()

        val messages = pendingMessagesMap.getOrPut(senderUsername) { mutableListOf() }
        messages.add(text)

        val inboxStyle = NotificationCompat.InboxStyle()
        messages.forEach { msg -> inboxStyle.addLine(msg) }
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
            .setOnlyAlertOnce(false)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setNumber(messages.size)  // 👈 СЧЕТЧИК НА ИКОНКЕ БУДЕТ ОТОБРАЖАТЬСЯ ИЗ ЭТОГО УВЕДОМЛЕНИЯ

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(summaryId, summaryBuilder.build())

        // 👇 ТОЛЬКО ОБНОВЛЯЕМ СЧЕТЧИК В ПАМЯТИ, БЕЗ ДОПОЛНИТЕЛЬНОГО УВЕДОМЛЕНИЯ
        updateTotalBadgeCount(this)
    }

    private fun createNotificationChannels() {
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

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(messageChannel)
        notificationManager.createNotificationChannel(callChannel)
    }

    override fun onNewToken(token: String) {
        Log.d("FCM", "New FCM token: $token")
        val prefsManager = PrefsManager(this)
        val currentUser = prefsManager.username ?: return
        sendFcmTokenToServerWithRetry(currentUser, token, 0)
    }

    private fun sendFcmTokenToServerWithRetry(username: String, fcmToken: String, attempt: Int) {
        val maxRetries = 3
        val baseDelay = 3000L

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val userService = RetrofitClient.getClient().create(UserService::class.java)
                val request = mapOf("username" to username, "fcmToken" to fcmToken)
                val response = userService.updateFcmToken(request)

                if (response.isSuccessful) {
                    Log.d("FCM", "✅ FCM token sent to server for user: $username")
                } else {
                    Log.e("FCM", "❌ Failed to send FCM token: ${response.code()}")
                    if (attempt < maxRetries) {
                        delay(baseDelay * (attempt + 1))
                        sendFcmTokenToServerWithRetry(username, fcmToken, attempt + 1)
                    }
                }
            } catch (e: Exception) {
                Log.e("FCM", "❌ Error sending FCM token: ${e.message}")
                if (attempt < maxRetries) {
                    delay(baseDelay * (attempt + 1))
                    sendFcmTokenToServerWithRetry(username, fcmToken, attempt + 1)
                }
            }
        }
    }

    private fun handleServerRestart() {
        val prefsManager = PrefsManager(this)
        prefsManager.username ?: return

        val bgIntent = Intent(this, MessengerService::class.java)
        bgIntent.action = MessengerService.ACTION_APP_BACKGROUND
        startService(bgIntent)

        val delays = listOf(1000L, 2000L, 4000L)
        var index = 0

        fun scheduleForeground() {
            if (index < delays.size) {
                Handler(Looper.getMainLooper()).postDelayed({
                    val fgIntent = Intent(this, MessengerService::class.java)
                    fgIntent.action = MessengerService.ACTION_APP_FOREGROUND
                    startService(fgIntent)
                    index++
                    scheduleForeground()
                }, delays[index])
            }
        }
        scheduleForeground()
    }
}