package com.messenger.messengerclient.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.messenger.messengerclient.MainActivity
import com.messenger.messengerclient.R
import com.messenger.messengerclient.data.local.AppDatabase
import com.messenger.messengerclient.data.model.Message
import com.messenger.messengerclient.network.RetrofitClient
import com.messenger.messengerclient.utils.ActivityCounter
import com.messenger.messengerclient.utils.PrefsManager
import com.messenger.messengerclient.websocket.WebSocketManager
import com.messenger.messengerclient.websocket.WebSocketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MessengerService : Service() {

    companion object {
        private const val TAG = "MessengerService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "messenger_service"
        const val ACTION_START = "start_service"
        const val ACTION_STOP = "stop_service"
        const val ACTION_APP_BACKGROUND = "app_background"
        const val ACTION_APP_FOREGROUND = "app_foreground"
    }

    private lateinit var prefsManager: PrefsManager
    private val db by lazy { AppDatabase.getInstance(this) }
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var isExplicitStop = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var tokenCheckHandler: Handler? = null
    private var tokenCheckRunnable: Runnable? = null
    private var isWebSocketConnecting = false

    // 👇 НОВЫЕ ФЛАГИ ДЛЯ ЗАЩИТЫ ОТ RACE CONDITION
    private var isDisconnecting = false
    private var pendingReconnect = false

    // Слушатель статусов для сервиса
    private val serviceStatusListener: (Message) -> Unit = { updatedMessage ->
        Log.d(TAG, "🔄 Service status update: ${updatedMessage.id} -> ${updatedMessage.status}")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                db.messageDao().updateMessageStatusAndRead(
                    messageId = updatedMessage.id!!,
                    status = updatedMessage.status,
                    isRead = updatedMessage.isRead
                )
                Log.d(TAG, "✅ Message ${updatedMessage.id} status updated to ${updatedMessage.status} in DB")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to update message status in DB: ${e.message}")
            }
        }
    }

    // 👇 СЛУШАТЕЛЬ СООБЩЕНИЙ ДЛЯ СЕРВИСА
    private val serviceMessageListener: (Message) -> Unit = { message ->
        val currentUser = prefsManager.username
        Log.d(TAG, "📨 Service message: ${message.id} from ${message.senderUsername}")

        // Если мы получатель - отправляем DELIVERED
        if (message.receiverUsername == currentUser && message.senderUsername != currentUser) {
            Log.d(TAG, "📲 Sending DELIVERED from service")

            CoroutineScope(Dispatchers.IO).launch {
                message.id?.let { messageId ->
                    db.messageDao().updateMessageStatusAndRead(
                        messageId = messageId,
                        status = "DELIVERED",
                        isRead = false
                    )

                    Handler(Looper.getMainLooper()).post {
                        WebSocketService.getInstance().sendStatusConfirmation(
                            messageId = messageId,
                            status = "DELIVERED",
                            username = currentUser
                        )
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "✅ Service created")
        prefsManager = PrefsManager(this)

        acquireWakeLock()

        ActivityCounter.clearListeners()

        ActivityCounter.addListener { isForeground ->
            val intent = Intent(this@MessengerService, MessengerService::class.java)
            intent.action = if (isForeground) ACTION_APP_FOREGROUND else ACTION_APP_BACKGROUND

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                Log.d(TAG, "✅ Intent sent: ${intent.action}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to send intent: ${e.message}")
            }
        }

        // 👇 ДОБАВЛЯЕМ СЛУШАТЕЛИ В СЕРВИС
        WebSocketService.getInstance().addStatusListener(serviceStatusListener)
        WebSocketService.getInstance().setMessageListener(serviceMessageListener)

        startTokenChecker()
        registerNetworkCallback()
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Messenger::KeepAlive"
            )
            wakeLock?.acquire(10 * 60 * 1000L)
            Log.d(TAG, "🔋 WakeLock ACQUIRED for 10 min")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                wakeLock = null
                Log.d(TAG, "🔋 WakeLock RELEASED")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to release WakeLock: ${e.message}")
        }
    }

    // 👇 НОВЫЙ МЕТОД: безопасное отключение WebSocket
    private fun disconnectWebSocket() {
        if (isDisconnecting) {
            Log.d(TAG, "⚠️ Already disconnecting, skipping")
            return
        }
        isDisconnecting = true
        pendingReconnect = false

        Log.d(TAG, "🔌 Disconnecting WebSocket with delay")
        WebSocketManager.disconnect()

        // Даем время на полное отключение
        Handler(Looper.getMainLooper()).postDelayed({
            isDisconnecting = false
            if (pendingReconnect) {
                Log.d(TAG, "🔄 Pending reconnect detected, executing now")
                pendingReconnect = false
                performConnectWebSocket()
            } else {
                Log.d(TAG, "✅ WebSocket fully disconnected")
            }
        }, 1000)
    }

    // 👇 НОВЫЙ МЕТОД: безопасное подключение WebSocket
    private fun performConnectWebSocket() {
        if (isDisconnecting) {
            Log.d(TAG, "⏳ Currently disconnecting, will reconnect after")
            pendingReconnect = true
            return
        }

        if (isWebSocketConnecting) {
            Log.d(TAG, "⚠️ WebSocket connection already in progress, skipping")
            return
        }

        val token = prefsManager.authToken
        val username = prefsManager.username

        if (!token.isNullOrEmpty() && !username.isNullOrEmpty()) {
            Log.d(TAG, "🔗 Connecting WebSocket from service")

            val service = WebSocketService.getInstance()
            service.setContext(this)

            if (service.isConnected()) {
                Log.d(TAG, "✅ WebSocket already connected")
                return
            }

            isWebSocketConnecting = true
            service.connect(token, username)

            Handler(Looper.getMainLooper()).postDelayed({
                if (service.isConnected()) {
                    Log.d(TAG, "✅ WebSocket connected")
                } else {
                    Log.w(TAG, "⚠️ WebSocket not connected after delay")
                }
                isWebSocketConnecting = false
            }, 3000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "🔄 onStartCommand: ${intent?.action}")
        ensureForegroundStarted()

        if (intent == null) {
            restoreService()
            return START_STICKY
        }

        when (intent.action) {
            ACTION_START -> {
                Log.d(TAG, "▶️ Starting foreground service")
                acquireWakeLock()
                startForegroundService()
                performConnectWebSocket()
            }

            ACTION_STOP -> {
                Log.d(TAG, "⏹️ Stopping service (explicit)")
                isExplicitStop = true
                Handler(Looper.getMainLooper()).postDelayed({
                    WebSocketManager.disconnect()
                    releaseWakeLock()
                    stopService()
                }, 500)
                return START_NOT_STICKY
            }

            ACTION_APP_BACKGROUND -> {
                Log.d(TAG, "📱 App went to BACKGROUND")
                disconnectWebSocket()
                Handler(Looper.getMainLooper()).postDelayed({
                    updateLastSeenOnServer()
                }, 1000)
            }

            ACTION_APP_FOREGROUND -> {
                Log.d(TAG, "📱 App returned to FOREGROUND")
                performConnectWebSocket()
            }
        }

        return START_STICKY
    }

    private fun restoreService() {
        Log.d(TAG, "🔄 Restoring service state")

        val token = prefsManager.authToken
        val username = prefsManager.username

        if (!token.isNullOrEmpty() && !username.isNullOrEmpty()) {
            val service = WebSocketService.getInstance()
            service.setContext(this)

            val isAppInForeground = ActivityCounter.isAppInForeground()
            if (isAppInForeground && !service.isConnected()) {
                performConnectWebSocket()
            }

            Log.d(TAG, "✅ Service restored for user: $username (foreground: $isAppInForeground)")
        }
    }

    private fun startForegroundService() {
        Log.d(TAG, "📱 Creating notification channel...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                Log.w(TAG, "⚠️ No notification permission on Android 13+")
            }
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Messenger Service",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Синхронизация сообщений"
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setAllowBubbles(true)
                }
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            Log.d(TAG, "✅ Notification channel created")
        }

        val iconId = try {
            R.drawable.app_icon
        } catch (_: Exception) {
            try {
                R.mipmap.ic_launcher
            } catch (_: Exception) {
                android.R.drawable.ic_dialog_info
            }
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Messenger - Активное соединение")
            .setContentText("Синхронизация сообщений и статусов")
            .setSmallIcon(iconId)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(true)
            .setSilent(true)
            .build()

        Log.d(TAG, "📋 Notification created, starting foreground...")
        try {
            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "✅ Service now in foreground")
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ SecurityException when starting foreground: ${e.message}")
            stopSelf()
        }
    }

    private fun stopService() {
        Log.d(TAG, "🛑 Stopping service")

        WebSocketManager.disconnect()
        releaseWakeLock()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        stopSelf()
        Log.d(TAG, "✅ Service stopped")
    }

    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "📡 Network available - reconnecting WebSocket")
                    reconnectWebSocket()
                }

                override fun onLost(network: Network) {
                    Log.d(TAG, "📡 Network lost")
                }
            }
            connectivityManager?.registerDefaultNetworkCallback(networkCallback!!)
            Log.d(TAG, "✅ Network callback registered")
        }
    }

    private fun reconnectWebSocket() {
        val token = prefsManager.authToken
        val username = prefsManager.username

        if (!token.isNullOrEmpty() && !username.isNullOrEmpty()) {
            Log.d(TAG, "🔗 Attempting WebSocket reconnection for $username")
            Handler(Looper.getMainLooper()).postDelayed({
                performConnectWebSocket()
            }, 2000)
        }
    }

    private fun unregisterNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && networkCallback != null) {
            connectivityManager?.unregisterNetworkCallback(networkCallback!!)
            networkCallback = null
            Log.d(TAG, "✅ Network callback unregistered")
        }
    }

    private fun updateLastSeenOnServer() {
        val username = prefsManager.username
        if (!username.isNullOrEmpty()) {
            Log.d(TAG, "⏰ Updating last seen for $username")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val userService = RetrofitClient.getClient().create(UserService::class.java)
                    userService.updateLastSeen(username)
                    Log.d(TAG, "✅ Last seen updated")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error updating last seen", e)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTokenChecker()
        ActivityCounter.removeListener { }

        WebSocketService.getInstance().removeStatusListener(serviceStatusListener)
        // 👇 НЕ удаляем messageListener, так как он может быть нужен другим компонентам

        Log.d(TAG, "💀 Service destroyed, isExplicitStop: $isExplicitStop")

        if (!isExplicitStop) {
            Log.d(TAG, "⚠️ Service destroyed by system, releasing WakeLock")
            releaseWakeLock()
        }

        unregisterNetworkCallback()

        Log.d(TAG, if (isExplicitStop) "🔚 Service stopped explicitly"
        else "🔄 Service may be restarted by system")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "🗑️ App removed from recents - UPDATING LAST SEEN")
        ActivityCounter.reset()
        updateLastSeenOnServer()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureForegroundStarted() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val manager = getSystemService(NotificationManager::class.java)
                    if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                        val channel = NotificationChannel(
                            CHANNEL_ID,
                            "Messenger Service",
                            NotificationManager.IMPORTANCE_NONE
                        ).apply {
                            description = "Фоновое соединение"
                            setShowBadge(false)
                            lockscreenVisibility = Notification.VISIBILITY_SECRET
                            setSound(null, null)
                        }
                        manager.createNotificationChannel(channel)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Channel creation failed, continuing: ${e.message}")
                }
            }

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Messenger")
                .setContentText(" ")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setShowWhen(false)
                .build()

            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "✅ Foreground started")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            Log.d(TAG, "✅ Notification hidden")

        } catch (e: Exception) {
            Log.e(TAG, "❌ CRITICAL: Cannot start foreground: ${e.message}", e)
            stopSelf()
        }
    }

    private fun startTokenChecker() {
        tokenCheckHandler = Handler(Looper.getMainLooper())
        tokenCheckRunnable = object : Runnable {
            override fun run() {
                checkAndRefreshToken()
                tokenCheckHandler?.postDelayed(this, 30 * 60 * 1000L)
            }
        }
        tokenCheckHandler?.post(tokenCheckRunnable!!)
        Log.d(TAG, "⏰ Token checker started")
    }

    private fun stopTokenChecker() {
        tokenCheckHandler?.removeCallbacksAndMessages(null)
        tokenCheckRunnable = null
        Log.d(TAG, "⏰ Token checker stopped")
    }

    private fun checkAndRefreshToken() {
        if (prefsManager.shouldRefreshToken()) {
            Log.d(TAG, "🔄 Token needs refresh, attempting...")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val success = refreshTokenSync()
                    if (success) {
                        Log.d(TAG, "✅ Token refreshed, reconnecting WebSocket")
                        reconnectWebSocketWithNewToken()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Token check failed", e)
                }
            }
        }
    }

    private suspend fun refreshTokenSync(): Boolean {
        val refreshToken = prefsManager.refreshToken
        if (refreshToken.isNullOrEmpty()) return false

        try {
            val authService = RetrofitClient.getClient().create(AuthService::class.java)
            val response = authService.refreshToken(mapOf("refreshToken" to refreshToken))

            if (response.isSuccessful) {
                val authResponse = response.body()!!
                prefsManager.saveTokens(
                    authResponse.accessToken,
                    authResponse.refreshToken,
                    authResponse.expiresIn
                )
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Refresh token error", e)
        }
        return false
    }

    private fun reconnectWebSocketWithNewToken() {
        val token = prefsManager.authToken
        val username = prefsManager.username

        if (!token.isNullOrEmpty() && !username.isNullOrEmpty()) {
            Log.d(TAG, "🔗 Reconnecting WebSocket with new token")
            val wsService = WebSocketService.getInstance()

            wsService.disconnect()

            Handler(Looper.getMainLooper()).postDelayed({
                wsService.connect(token, username)
                Log.d(TAG, "✅ WebSocket reconnected with new token")
            }, 1000)
        }
    }
}