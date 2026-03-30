package com.messenger.messengerclient.messaging

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.drawable.IconCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.messenger.messengerclient.R
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
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import androidx.core.graphics.scale

class MessengerFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val PREFS_NAME = "fcm_notification_prefs"
        private const val KEY_TOTAL_UNREAD = "total_unread_count"

        private val pendingMessagesMap = ConcurrentHashMap<String, MutableList<PendingMessage>>()
        private val pendingNewMessagesFromBackground = ConcurrentHashMap<String, Boolean>()
        private val avatarCache = ConcurrentHashMap<String, Bitmap>()

        data class PendingMessage(
            val text: String,
            val messageId: Long,
            val timestamp: Long = System.currentTimeMillis()
        )

        fun markNewMessageForUserInBackground(username: String) {
            pendingNewMessagesFromBackground[username] = true
            Log.d("FCM", "📌 Marked new message for user: $username")
        }

        fun consumeNewMessageFlag(username: String): Boolean {
            return pendingNewMessagesFromBackground.remove(username) ?: false
        }

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

        private fun updateTotalBadgeCount(context: Context) {
            val totalMessages = pendingMessagesMap.values.sumOf { it.size }
            val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit { putInt(KEY_TOTAL_UNREAD, totalMessages) }
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
                        targetUsername,
                        messageId ?: 0
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

    private fun handleNewMessage(
        sender: String,
        text: String,
        senderUsername: String,
        targetUsername: String,
        messageId: Long
    ) {
        val currentUser = PrefsManager(this).username
        if (senderUsername == currentUser) return

        if (ActivityCounter.isChatWithUserOpen(senderUsername)) {
            Log.d("FCM", "Chat open, skipping notification for: $senderUsername")
            return
        }

        markNewMessageForUserInBackground(senderUsername)

        val messages = pendingMessagesMap.getOrPut(senderUsername) { mutableListOf() }
        messages.add(PendingMessage(text, messageId))

        // 👇 ПЕРЕДАЕМ text
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

        val messages = pendingMessagesMap[senderUsername] ?: mutableListOf()
        val messageTexts = messages.map { it.text }
        val lastMessageText = messageTexts.lastOrNull() ?: text

        val avatarBitmap = getSenderAvatar(senderUsername)

        // Масштабируем аватарку для уведомления
        val targetSize = (96 * resources.displayMetrics.density).toInt()
        val scaledAvatar = avatarBitmap?.let { Bitmap.createScaledBitmap(it, targetSize, targetSize, true) }

        val builder = NotificationCompat.Builder(this, "messenger_channel")
            .setSmallIcon(R.drawable.app_icon_1)
            .setLargeIcon(scaledAvatar)
            .setContentTitle(sender)
            .setContentText(lastMessageText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setGroup(groupKey)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setNumber(messages.size)
            .setShowWhen(true)

        // Если несколько сообщений, показываем InboxStyle
        if (messages.size > 1) {
            val inboxStyle = NotificationCompat.InboxStyle()
            messageTexts.takeLast(5).forEach { msg ->
                inboxStyle.addLine(msg)
            }
            inboxStyle.setBigContentTitle(sender)
            inboxStyle.setSummaryText("${messages.size} сообщений")
            builder.setStyle(inboxStyle)
            builder.setContentText("${messages.size} новых сообщений от $sender")
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(lastMessageText))
        }

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(summaryId, builder.build())

        updateTotalBadgeCount(this)
    }

    private fun getSenderAvatar(senderUsername: String): Bitmap? {
        try {
            val avatarFile = File(filesDir, "avatar_${senderUsername}.jpg")
            if (avatarFile.exists()) {
                val bitmap = BitmapFactory.decodeFile(avatarFile.absolutePath)

                // Обрезаем до квадрата
                val size = minOf(bitmap.width, bitmap.height)
                val x = (bitmap.width - size) / 2
                val y = (bitmap.height - size) / 2
                val cropped = Bitmap.createBitmap(bitmap, x, y, size, size)

                // Масштабируем до нужного размера
                val targetSize = (96 * resources.displayMetrics.density).toInt()
                val scaled = Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)

                // Делаем круглой (как в ChatActivity)
                return getRoundedBitmap(scaled)
            }
        } catch (e: Exception) {
            Log.e("FCM", "Error loading avatar: ${e.message}")
        }
        return null
    }

    private fun getRoundedBitmap(bitmap: Bitmap): Bitmap {
        val size = bitmap.width
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val rect = android.graphics.Rect(0, 0, size, size)
        val rectF = android.graphics.RectF(rect)

        paint.isAntiAlias = true
        canvas.drawARGB(0, 0, 0, 0)
        canvas.drawRoundRect(rectF, size / 2f, size / 2f, paint)

        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, rect, rect, paint)

        return output
    }


        private fun loadAvatarBitmap(username: String, callback: (Bitmap?) -> Unit) {
        avatarCache[username]?.let {
            callback(it)
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cachedFile = File(filesDir, "avatar_$username.jpg")
                if (cachedFile.exists()) {
                    val bitmap = BitmapFactory.decodeFile(cachedFile.absolutePath)
                    avatarCache[username] = bitmap
                    runOnUiThread { callback(bitmap) }
                    return@launch
                }

                val prefsManager = PrefsManager(this@MessengerFirebaseMessagingService)
                val token = prefsManager.authToken
                if (token.isNullOrEmpty()) {
                    runOnUiThread { callback(null) }
                    return@launch
                }

                val baseUrl = com.messenger.messengerclient.config.ApiConfig.BASE_URL
                val userService = RetrofitClient.getClientWithAuth(token).create(UserService::class.java)
                val response = userService.getUser(username)

                if (response.isSuccessful) {
                    val user = response.body()
                    if (!user?.avatarUrl.isNullOrEmpty()) {
                        val fullUrl = baseUrl + user.avatarUrl
                        val url = URL(fullUrl)
                        val connection = url.openConnection()
                        connection.connect()
                        val inputStream = connection.getInputStream()
                        val file = File(filesDir, "avatar_$username.jpg")
                        FileOutputStream(file).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                        avatarCache[username] = bitmap
                        runOnUiThread { callback(bitmap) }
                        return@launch
                    }
                }
                runOnUiThread { callback(null) }
            } catch (e: Exception) {
                Log.e("FCM", "Error loading avatar: ${e.message}")
                runOnUiThread { callback(null) }
            }
        }
    }

    private fun runOnUiThread(action: () -> Unit) {
        Handler(Looper.getMainLooper()).post(action)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val messageChannel = NotificationChannel(
                "messenger_channel",
                "Сообщения",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о новых сообщениях"
                enableLights(true)
                lightColor = ContextCompat.getColor(this@MessengerFirebaseMessagingService, R.color.purple_500)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 200)
                setShowBadge(true)
            }

            val callChannel = NotificationChannel(
                "messenger_calls",
                "Звонки",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о входящих звонках"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 500, 1000)
            }

            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(messageChannel)
            notificationManager.createNotificationChannel(callChannel)
        }
    }

    @SuppressLint("FullScreenIntentPolicy")
    private fun handleIncomingCall(caller: String, targetUsername: String, callType: String) {
        val prefsManager = PrefsManager(this)
        val currentUser = prefsManager.username

        if (currentUser != targetUsername || ActivityCounter.isInCall()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.e("FCM", "❌ No notification permission")
                return
            }
        }

        createNotificationChannels()

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

        val builder = NotificationCompat.Builder(this, "messenger_calls")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("📞 Входящий звонок")
            .setContentText("$caller звонит вам")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setFullScreenIntent(pendingIntent, true)
            .setTimeoutAfter(30000)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId("messenger_calls")
        }

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1001, builder.build())

        startActivity(callIntent)
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