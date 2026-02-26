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
import com.messenger.messengerclient.network.RetrofitClient
import com.messenger.messengerclient.service.MessengerService
import com.messenger.messengerclient.service.UserService
import com.messenger.messengerclient.ui.CallActivity
import com.messenger.messengerclient.ui.ChatActivity
import com.messenger.messengerclient.utils.ActivityCounter
import com.messenger.messengerclient.utils.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MessengerFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        Log.d("FCM", "📬 Message received: ${message.data}")

        val type = message.data["type"]
        val action = message.data["action"]
        val sender = message.data["sender"]
        val text = message.data["message"]
        val senderUsername = message.data["senderUsername"]
        val callerUsername = message.data["callerUsername"]
        val targetUsername = message.data["targetUsername"]
        val deepLinkAction = message.data["deepLinkAction"]
        val callType = message.data["callType"]

        Log.d("FCM", "Type: $type, Action: $action, DeepLinkAction: $deepLinkAction, Target: $targetUsername")

        // НОВЫЙ ОБРАБОТЧИК ДЛЯ КОМАНДЫ ПЕРЕПОДКЛЮЧЕНИЯ
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
                if (deepLinkAction == "OPEN_CHAT" && targetUsername != null && senderUsername != null) {
                    handleNewMessage(sender ?: "Unknown", text ?: "", senderUsername, targetUsername)
                }
            }
            else -> {
                Log.w("FCM", "Unknown FCM type: $type")
            }
        }
    }

    // НОВЫЙ МЕТОД
    private fun handleServerRestart() {
        Log.e("FCM", "🔥🔥🔥 FCM: СЕРВЕР ПЕРЕЗАГРУЖЕН - ДЕЛАЕМ BACKGROUND/FOREGROUND!")

        val prefsManager = PrefsManager(this)
        val currentUser = prefsManager.username

        if (currentUser.isNullOrEmpty()) {
            Log.d("FCM", "Пользователь не залогинен, игнорируем")
            return
        }

        Log.d("FCM", "Пользователь $currentUser онлайн? Выполняем BACKGROUND...")

        // Отправляем команду BACKGROUND в сервис
        val bgIntent = Intent(this, MessengerService::class.java)
        bgIntent.action = MessengerService.ACTION_APP_BACKGROUND
        startService(bgIntent)
        Log.d("FCM", "✅ Команда BACKGROUND отправлена в сервис")

        // Через 5 секунд FOREGROUND
        Handler(Looper.getMainLooper()).postDelayed({
            Log.d("FCM", "⏰ Прошло 5 секунд, отправляю FOREGROUND...")
            val fgIntent = Intent(this, MessengerService::class.java)
            fgIntent.action = MessengerService.ACTION_APP_FOREGROUND
            startService(fgIntent)
            Log.d("FCM", "✅ Команда FOREGROUND отправлена - переподключение выполнено")
        }, 5000)
    }

    private fun handleIncomingCall(caller: String, targetUsername: String, callType: String) {
        Log.d("FCM", "📞 Incoming call from: $caller, type: $callType")

        val prefsManager = PrefsManager(this)
        val currentUser = prefsManager.username

        // Проверяем что звонок для текущего пользователя
        if (currentUser != targetUsername) {
            Log.d("FCM", "Call not for current user: $targetUsername, we are: $currentUser")
            return
        }

        // Проверяем не в звонке ли уже
        if (ActivityCounter.isInCall()) {
            Log.d("FCM", "Already in call, ignoring incoming call")
            return
        }

        // Создаем Intent для CallActivity
        val callIntent = Intent(this, CallActivity::class.java).apply {
            putExtra(CallActivity.EXTRA_CALL_TYPE, callType)
            putExtra(CallActivity.EXTRA_TARGET_USER, caller)
            putExtra(CallActivity.EXTRA_IS_INCOMING, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        // Создаем PendingIntent с уникальным requestCode
        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            callIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
        )

        // Создаем уведомление о звонке
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
            .setFullScreenIntent(pendingIntent, true) // Важно! Показывает на весь экран
            .setTimeoutAfter(30000) // 30 секунд

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Создаем отдельный канал для звонков (если еще не создан)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val callChannel = NotificationChannel(
                "messenger_calls",
                "Звонки",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о входящих звонках"
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 500, 1000) // Вибрация
            }
            notificationManager.createNotificationChannel(callChannel)
        }

        // Показываем уведомление
        notificationManager.notify(1001, notificationBuilder.build())

        // Также запускаем Activity сразу
        startActivity(callIntent)

        Log.d("FCM", "✅ Call notification shown and activity started")
    }

    private fun handleNewMessage(sender: String, text: String, senderUsername: String, targetUsername: String) {
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
        showMessageNotification(sender, text, targetUsername)
    }

    private fun showMessageNotification(sender: String, text: String, targetUsername: String) {
        // Intent для ChatActivity с правильными флагами
        val chatIntent = Intent(this, ChatActivity::class.java).apply {
            putExtra("RECEIVER_USERNAME", targetUsername)
            putExtra("RECEIVER_DISPLAY_NAME", sender)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or  // 👈 ДОБАВЛЕНО
                    Intent.FLAG_ACTIVITY_SINGLE_TOP     // 👈 ДОБАВЛЕНО
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
            // Канал для сообщений
            val messageChannel = NotificationChannel(
                "messenger_channel",
                "Сообщения",
                NotificationManager.IMPORTANCE_HIGH
            )

            // Канал для звонков (создаем отдельно)
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
        }
    }

    override fun onNewToken(token: String) {
        Log.d("FCM", "New FCM token: $token")

        // Сохраняем токен только если пользователь залогинен
        val prefsManager = PrefsManager(this)
        val currentUser = prefsManager.username

        if (!currentUser.isNullOrEmpty()) {
            // Отправляем токен на сервер для текущего пользователя
            sendFcmTokenToServer(currentUser, token)
        } else {
            Log.d("FCM", "User not logged in, not sending token to server")
        }
    }

    private fun sendFcmTokenToServer(username: String, fcmToken: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val userService = RetrofitClient.getClient().create(UserService::class.java)

                val request = mapOf(
                    "username" to username,
                    "fcmToken" to fcmToken
                )

                val response = userService.updateFcmToken(request)

                if (response.isSuccessful) {
                    Log.d("FCM", "✅ FCM token sent to server for user: $username")
                } else {
                    Log.e("FCM", "❌ Failed to send FCM token: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e("FCM", "❌ Error sending FCM token: ${e.message}")
            }
        }
    }
}