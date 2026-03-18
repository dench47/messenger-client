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
import kotlinx.coroutines.launch
import kotlin.jvm.java

class MessengerFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        Log.d("FCM", "🔥🔥🔥🔥🔥🔥🔥🔥🔥🔥🔥🔥🔥🔥🔥🔥🔥🔥🔥🔥")
        Log.d("FCM", "🔥 FCM RECEIVED AT: ${System.currentTimeMillis()}")
        Log.d("FCM", "🔥 FULL DATA: ${message.data}")
        Log.d("FCM", "🔥🔥🔥🔥🔥🔥🔥🔥🔥🔥🔥🔥🔥🔥🔥🔥🔥🔥🔥🔥")

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

        Log.d("FCM", "📋 PARSED DATA:")
        Log.d("FCM", "   type: $type")
        Log.d("FCM", "   action: $action")
        Log.d("FCM", "   sender: $sender")
        Log.d("FCM", "   senderUsername: $senderUsername")
        Log.d("FCM", "   targetUsername: $targetUsername")
        Log.d("FCM", "   messageId: $messageId")
        Log.d("FCM", "   status: $status")
        Log.d("FCM", "   content: $content")

        if (type == "SERVER_RESTARTED" && action == "DO_BACKGROUND") {
            Log.d("FCM", "🔄 SERVER_RESTARTED received")
            handleServerRestart()
            return
        }

        when (type) {
            "INCOMING_CALL" -> {
                Log.d("FCM", "📞 INCOMING_CALL received")
                handleIncomingCall(
                    callerUsername ?: sender ?: "Unknown",
                    targetUsername ?: "",
                    callType ?: "audio"
                )
            }
            "NEW_MESSAGE" -> {
                Log.d("FCM", "📨 NEW_MESSAGE received")
                if (messageId != null && senderUsername != null) {
                    Log.d("FCM", "📨 Calling saveMessageAndSendDelivered for message $messageId")
                    saveMessageAndSendDelivered(
                        messageId = messageId,
                        senderUsername = senderUsername,
                        content = content,
                        timestamp = timestamp,
                        targetUsername = targetUsername ?: ""
                    )
                } else {
                    Log.e("FCM", "❌ NEW_MESSAGE missing required fields: messageId=$messageId, senderUsername=$senderUsername")
                }

                if (deepLinkAction == "OPEN_CHAT" && targetUsername != null && senderUsername != null) {
                    Log.d("FCM", "📨 OPEN_CHAT action")
                    handleNewMessage(sender ?: "Unknown", text ?: "", senderUsername, targetUsername)
                }
            }
            "STATUS_UPDATE" -> {
                Log.d("FCM", "📊 STATUS_UPDATE received")
                handleStatusUpdate(
                    messageId = messageId,
                    status = status,
                    senderUsername = senderUsername,
                    receiverUsername = targetUsername
                )
            }
            else -> {
                Log.w("FCM", "❓ Unknown FCM type: $type")
            }
        }
    }

    private fun handleStatusUpdate(
        messageId: Long?,
        status: String?,
        senderUsername: String?,
        receiverUsername: String?
    ) {
        Log.d("FCM", "📊📊📊📊📊📊📊📊📊📊📊📊📊📊📊📊")
        Log.d("FCM", "📊 handleStatusUpdate CALLED")
        Log.d("FCM", "📊 messageId: $messageId")
        Log.d("FCM", "📊 status: $status")
        Log.d("FCM", "📊 senderUsername: $senderUsername")
        Log.d("FCM", "📊 receiverUsername: $receiverUsername")
        Log.d("FCM", "📊📊📊📊📊📊📊📊📊📊📊📊📊📊📊📊")

        if (messageId == null || status == null || senderUsername == null || receiverUsername == null) {
            Log.e("FCM", "❌ Invalid STATUS_UPDATE data - missing required fields")
            return
        }

        val prefsManager = PrefsManager(this)
        val currentUser = prefsManager.username
        Log.d("FCM", "📊 currentUser: $currentUser")

        Log.d("FCM", "📊 STATUS UPDATE via FCM: message $messageId -> $status from $senderUsername to $receiverUsername")

        val updatedMessage = Message(
            id = messageId,
            content = "",
            senderUsername = senderUsername,
            receiverUsername = receiverUsername,
            timestamp = "",
            isRead = status == "READ",
            status = status
        )

        Log.d("FCM", "📊 Updating message in DB...")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(this@MessengerFirebaseMessagingService)
                db.messageDao().updateMessageStatusAndRead(
                    messageId = messageId,
                    status = status,
                    isRead = status == "READ"
                )
                Log.d("FCM", "✅ Message $messageId status updated to $status in DB via FCM")
            } catch (e: Exception) {
                Log.e("FCM", "❌ Error updating message status in DB: ${e.message}")
                e.printStackTrace()
            }
        }

        Log.d("FCM", "📊 Sending to statusListeners (count: ${WebSocketService.getInstance().getStatusListenersCount()})")
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
        Log.d("FCM", "📦📦📦📦📦📦📦📦📦📦📦📦📦📦📦📦")
        Log.d("FCM", "📦 saveMessageAndSendDelivered CALLED")
        Log.d("FCM", "📦 messageId: $messageId")
        Log.d("FCM", "📦 senderUsername: $senderUsername")
        Log.d("FCM", "📦 targetUsername: $targetUsername")
        Log.d("FCM", "📦 content: $content")
        Log.d("FCM", "📦 timestamp: $timestamp")
        Log.d("FCM", "📦📦📦📦📦📦📦📦📦📦📦📦📦📦📦📦")

        val prefsManager = PrefsManager(this)
        val currentUser = prefsManager.username
        Log.d("FCM", "📦 currentUser: $currentUser")

        if (currentUser.isNullOrEmpty()) {
            Log.e("FCM", "❌ Cannot save message: user not logged in")
            return
        }

        if (targetUsername != currentUser) {
            Log.e("FCM", "❌ Message not for current user: $targetUsername, we are: $currentUser")
            return
        }

        Log.d("FCM", "📦 Saving message $messageId from $senderUsername to DB")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(this@MessengerFirebaseMessagingService)
                Log.d("FCM", "📦 DB instance obtained")

                val existingMessage = db.messageDao().getMessageById(messageId)
                Log.d("FCM", "📦 existingMessage: $existingMessage")

                if (existingMessage != null) {
                    Log.d("FCM", "📦 Message $messageId already exists in DB with status ${existingMessage.status}")

                    if (existingMessage.status != "DELIVERED" && existingMessage.status != "READ") {
                        Log.d("FCM", "📦 Existing message not DELIVERED/READ, calling sendDeliveredConfirmation")
                        sendDeliveredConfirmation(messageId, senderUsername)
                    } else {
                        Log.d("FCM", "📦 Message already has final status ${existingMessage.status}, skipping")
                    }
                    return@launch
                }

                Log.d("FCM", "📦 Creating new Message object")
                val message = Message(
                    id = messageId,
                    content = content,
                    senderUsername = senderUsername,
                    receiverUsername = currentUser,
                    timestamp = timestamp,
                    isRead = false,
                    status = "SENT"
                )

                Log.d("FCM", "📦 Inserting message into DB")
                db.messageDao().insertMessage(message.toLocal())
                Log.d("FCM", "✅ Message $messageId saved to DB")

                Log.d("FCM", "📦 Calling sendDeliveredConfirmation")
                sendDeliveredConfirmation(messageId, senderUsername)

            } catch (e: Exception) {
                Log.e("FCM", "❌ Error saving message: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun sendDeliveredConfirmation(messageId: Long, senderUsername: String) {
        Log.d("FCM", "📤📤📤📤📤📤📤📤📤📤📤📤📤📤📤📤")
        Log.d("FCM", "📤 sendDeliveredConfirmation CALLED")
        Log.d("FCM", "📤 messageId: $messageId")
        Log.d("FCM", "📤 senderUsername: $senderUsername")
        Log.d("FCM", "📤📤📤📤📤📤📤📤📤📤📤📤📤📤📤📤")

        val prefsManager = PrefsManager(this)
        val currentUser = prefsManager.username
        Log.d("FCM", "📤 currentUser: $currentUser")

        if (currentUser.isNullOrEmpty()) {
            Log.e("FCM", "❌ Cannot send DELIVERED: user not logged in")
            return
        }

        Log.d("FCM", "📤 Checking if chat with $senderUsername is open: ${ActivityCounter.isChatWithUserOpen(senderUsername)}")

        if (ActivityCounter.isChatWithUserOpen(senderUsername)) {
            Log.d("FCM", "📤 Already in chat with $senderUsername, will send READ instead of DELIVERED")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(this@MessengerFirebaseMessagingService)
                Log.d("FCM", "📤 DB instance obtained")

                val existingMessage = db.messageDao().getMessageById(messageId)
                Log.d("FCM", "📤 existingMessage status: ${existingMessage?.status}")

                if (existingMessage?.status == "READ" || existingMessage?.status == "DELIVERED") {
                    Log.d("FCM", "📤 Message $messageId already has status ${existingMessage.status}, skipping")
                    return@launch
                }

                Log.d("FCM", "📤 Updating message status in DB to DELIVERED")
                db.messageDao().updateMessageStatusAndRead(
                    messageId = messageId,
                    status = "DELIVERED",
                    isRead = false
                )
                Log.d("FCM", "✅ Message $messageId status updated to DELIVERED in DB")

                // Пытаемся отправить через WebSocket
                Handler(Looper.getMainLooper()).post {
                    try {
                        Log.d("FCM", "📤 Getting WebSocket service...")
                        val webSocketService = WebSocketManager.getService()
                        val isConnected = webSocketService?.isConnected() ?: false
                        Log.d("FCM", "📤 WebSocket connected: $isConnected")

                        if (webSocketService != null && isConnected) {
                            Log.d("FCM", "📤 Sending DELIVERED via WebSocket for message $messageId")
                            val success = webSocketService.sendStatusConfirmation(
                                messageId,
                                "DELIVERED",
                                currentUser
                            )
                            Log.d("FCM", "📤 WebSocket send result: $success")
                        } else {
                            Log.e("FCM", "❌ WebSocket not connected, cannot send DELIVERED")

                            // 👇 ОТПРАВЛЯЕМ DELIVERED ЧЕРЕЗ HTTP (REST API) КАК ЗАПАСНОЙ ВАРИАНТ
                            sendDeliveredViaHttp(messageId, senderUsername, currentUser)
                        }
                    } catch (e: Exception) {
                        Log.e("FCM", "❌ Failed to send via WebSocket: ${e.message}")
                        e.printStackTrace()

                        // 👇 ОТПРАВЛЯЕМ DELIVERED ЧЕРЕЗ HTTP (REST API) КАК ЗАПАСНОЙ ВАРИАНТ
                        sendDeliveredViaHttp(messageId, senderUsername, currentUser)
                    }
                }
            } catch (e: Exception) {
                Log.e("FCM", "❌ Error in sendDeliveredConfirmation: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun sendDeliveredViaHttp(messageId: Long, senderUsername: String, currentUser: String) {
        Log.d("FCM", "🌐 Attempting to send DELIVERED via HTTP for message $messageId")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Получаем токен
                val prefsManager = PrefsManager(this@MessengerFirebaseMessagingService)
                val token = prefsManager.authToken

                if (token.isNullOrEmpty()) {
                    Log.e("FCM", "❌ No auth token available")
                    return@launch
                }

                // Создаем DTO запрос
                val statusUpdate = StatusUpdateRequest(
                    messageId = messageId,
                    status = "DELIVERED",
                    username = currentUser
                )

                Log.d("FCM", "🌐 Sending request: $statusUpdate with token: ${token.take(20)}...")

                // Создаем клиент с заголовком авторизации
                val client = RetrofitClient.getClientWithAuth(token)
                val messageService = client.create(MessageService::class.java)

                val response = messageService.updateMessageStatus(statusUpdate)

                Log.d("FCM", "🌐 Response code: ${response.code()}")

                if (response.isSuccessful) {
                    Log.d("FCM", "✅ DELIVERED sent via HTTP for message $messageId")
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e("FCM", "❌ HTTP request failed: ${response.code()} - $errorBody")
                }
            } catch (e: Exception) {
                Log.e("FCM", "❌ HTTP error: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    private fun handleIncomingCall(caller: String, targetUsername: String, callType: String) {
        Log.d("FCM", "📞📞📞📞📞📞📞📞📞📞📞📞📞📞📞📞")
        Log.d("FCM", "📞 Incoming call from: $caller, type: $callType")
        Log.d("FCM", "📞 targetUsername: $targetUsername")
        Log.d("FCM", "📞📞📞📞📞📞📞📞📞📞📞📞📞📞📞📞")

        val prefsManager = PrefsManager(this)
        val currentUser = prefsManager.username
        Log.d("FCM", "📞 currentUser: $currentUser")

        if (currentUser != targetUsername) {
            Log.e("FCM", "📞 Call not for current user: $targetUsername, we are: $currentUser")
            return
        }

        if (ActivityCounter.isInCall()) {
            Log.d("FCM", "📞 Already in call, ignoring incoming call")
            return
        }

        Log.d("FCM", "📞 Creating call intent")
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
        Log.d("FCM", "📞 PendingIntent created")

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

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1001, notificationBuilder.build())
        Log.d("FCM", "📞 Notification shown")

        startActivity(callIntent)
        Log.d("FCM", "📞 Call activity started")
    }

    private fun handleNewMessage(sender: String, text: String, senderUsername: String, targetUsername: String) {
        Log.d("FCM", "📧📧📧📧📧📧📧📧📧📧📧📧📧📧📧📧")
        Log.d("FCM", "📧 handleNewMessage CALLED")
        Log.d("FCM", "📧 sender: $sender")
        Log.d("FCM", "📧 senderUsername: $senderUsername")
        Log.d("FCM", "📧 targetUsername: $targetUsername")
        Log.d("FCM", "📧 text: $text")
        Log.d("FCM", "📧📧📧📧📧📧📧📧📧📧📧📧📧📧📧📧")

        val currentUser = PrefsManager(this).username
        Log.d("FCM", "📧 currentUser: $currentUser")

        if (senderUsername == currentUser) {
            Log.d("FCM", "📧 Message from self - ignoring")
            return
        }

        if (ActivityCounter.isChatWithUserOpen(senderUsername)) {
            Log.d("FCM", "📧 Chat with sender already open - NO NOTIFICATION")
            return
        }

        Log.d("FCM", "📧 Showing message notification")
        showMessageNotification(sender, text, targetUsername)
    }

    private fun showMessageNotification(sender: String, text: String, targetUsername: String) {
        Log.d("FCM", "🔔🔔🔔🔔🔔🔔🔔🔔🔔🔔🔔🔔🔔🔔🔔🔔")
        Log.d("FCM", "🔔 showMessageNotification CALLED")
        Log.d("FCM", "🔔 sender: $sender")
        Log.d("FCM", "🔔 targetUsername: $targetUsername")
        Log.d("FCM", "🔔 text: $text")
        Log.d("FCM", "🔔🔔🔔🔔🔔🔔🔔🔔🔔🔔🔔🔔🔔🔔🔔🔔")

        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("RECEIVER_USERNAME", targetUsername)
            putExtra("RECEIVER_DISPLAY_NAME", sender)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        Log.d("FCM", "🔔 PendingIntent created")

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
        Log.d("FCM", "🔔 Notification shown")
    }

    private fun createNotificationChannel() {
        Log.d("FCM", "🔧 Creating notification channels")
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

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(messageChannel)
            notificationManager.createNotificationChannel(callChannel)
            Log.d("FCM", "✅ Notification channels created")
        }
    }

    override fun onNewToken(token: String) {
        Log.d("FCM", "🆕🆕🆕🆕🆕🆕🆕🆕🆕🆕🆕🆕🆕🆕🆕🆕")
        Log.d("FCM", "🆕 New FCM token: $token")
        Log.d("FCM", "🆕🆕🆕🆕🆕🆕🆕🆕🆕🆕🆕🆕🆕🆕🆕🆕")

        val prefsManager = PrefsManager(this)
        val currentUser = prefsManager.username
        Log.d("FCM", "🆕 currentUser: $currentUser")

        if (!currentUser.isNullOrEmpty()) {
            Log.d("FCM", "🆕 Sending token to server for user $currentUser")
            sendFcmTokenToServer(currentUser, token)
        } else {
            Log.d("FCM", "🆕 User not logged in, not sending token to server")
        }
    }

    private fun sendFcmTokenToServer(username: String, fcmToken: String) {
        Log.d("FCM", "📡📡📡📡📡📡📡📡📡📡📡📡📡📡📡📡")
        Log.d("FCM", "📡 sendFcmTokenToServer CALLED")
        Log.d("FCM", "📡 username: $username")
        Log.d("FCM", "📡 fcmToken: $fcmToken")
        Log.d("FCM", "📡📡📡📡📡📡📡📡📡📡📡📡📡📡📡📡")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val userService = RetrofitClient.getClient().create(UserService::class.java)
                Log.d("FCM", "📡 UserService created")

                val request = mapOf(
                    "username" to username,
                    "fcmToken" to fcmToken
                )
                Log.d("FCM", "📡 Request: $request")

                val response = userService.updateFcmToken(request)
                Log.d("FCM", "📡 Response code: ${response.code()}")

                if (response.isSuccessful) {
                    Log.d("FCM", "✅ FCM token sent to server for user: $username")
                } else {
                    Log.e("FCM", "❌ Failed to send FCM token: ${response.code()} - ${response.errorBody()?.string()}")
                }
            } catch (e: Exception) {
                Log.e("FCM", "❌ Error sending FCM token: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun handleServerRestart() {
        Log.d("FCM", "🔄🔄🔄🔄🔄🔄🔄🔄🔄🔄🔄🔄🔄🔄🔄🔄")
        Log.d("FCM", "🔄 SERVER RESTART HANDLER CALLED")
        Log.d("FCM", "🔄🔄🔄🔄🔄🔄🔄🔄🔄🔄🔄🔄🔄🔄🔄🔄")

        val prefsManager = PrefsManager(this)
        val currentUser = prefsManager.username
        Log.d("FCM", "🔄 currentUser: $currentUser")

        if (currentUser.isNullOrEmpty()) {
            Log.d("FCM", "🔄 User not logged in, ignoring")
            return
        }

        Log.d("FCM", "🔄 Sending BACKGROUND command to MessengerService")
        val bgIntent = Intent(this, MessengerService::class.java)
        bgIntent.action = MessengerService.ACTION_APP_BACKGROUND
        startService(bgIntent)
        Log.d("FCM", "✅ BACKGROUND command sent")

        Handler(Looper.getMainLooper()).postDelayed({
            Log.d("FCM", "🔄 5 seconds passed, sending FOREGROUND command")
            val fgIntent = Intent(this, MessengerService::class.java)
            fgIntent.action = MessengerService.ACTION_APP_FOREGROUND
            startService(fgIntent)
            Log.d("FCM", "✅ FOREGROUND command sent")
        }, 5000)
    }
}