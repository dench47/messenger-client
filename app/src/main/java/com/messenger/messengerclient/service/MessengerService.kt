package com.messenger.messengerclient.service

import android.app.*
import android.content.Context
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
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var isExplicitStop = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var tokenCheckHandler: Handler? = null
    private var tokenCheckRunnable: Runnable? = null
    private var isWebSocketConnecting = false

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

        startTokenChecker()
        registerNetworkCallback()
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Messenger::KeepAlive"
            )
            wakeLock?.acquire(10 * 60 * 1000L) // 10 минут
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "🔄 onStartCommand: ${intent?.action}")
        ensureForegroundStarted()

        if (intent == null) {
            // Сервис перезапущен системой
            restoreService()
            return START_STICKY
        }

        when (intent.action) {
            ACTION_START -> {
                Log.d(TAG, "▶️ Starting foreground service")
                acquireWakeLock()
                startForegroundService()
                // НЕ подключаем WebSocket здесь - MainActivity сделает это через ACTION_APP_FOREGROUND
            }

            ACTION_STOP -> {
                Log.d(TAG, "⏹️ Stopping service (explicit)")
                isExplicitStop = true
                // СНАЧАЛА отправляем offline статус
                sendOnlineStatus(false)
                // Ждем немного
                Handler(Looper.getMainLooper()).postDelayed({
                    // ПОТОМ отключаем WebSocket
                    WebSocketManager.disconnect()
                    releaseWakeLock()
                    stopService()
                }, 500)
                return START_NOT_STICKY
            }

            ACTION_APP_BACKGROUND -> {
                Log.d(TAG, "📱 App went to BACKGROUND - SWIPE LOGIC")
                // 1. Обновляем last seen
                updateLastSeenOnServer()
                // 2. Разрываем WebSocket
                WebSocketManager.disconnect()
                // ВСЁ!
            }

            ACTION_APP_FOREGROUND -> {
                Log.d(TAG, "📱 App returned to FOREGROUND")
                // 1. Подключаем WebSocket (если еще не подключен)
                connectWebSocket()
                // 2. sendOnlineStatus(true) вызывается ВНУТРИ connectWebSocket после подключения
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

            // Если приложение в фоне - НЕ восстанавливаем WebSocket
            val isAppInForeground = ActivityCounter.isAppInForeground()
            if (isAppInForeground && !service.isConnected()) {
                connectWebSocket()
            }

            Log.d(TAG, "✅ Service restored for user: $username (foreground: $isAppInForeground)")
        }
    }

    private fun connectWebSocket() {
        // Защита от множественных вызовов
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

            // ПРОВЕРЯЕМ, НЕ ПОДКЛЮЧЕН ЛИ УЖЕ
            if (service.isConnected()) {
                Log.d(TAG, "✅ WebSocket already connected")
                sendOnlineStatus(true)
                return
            }

            isWebSocketConnecting = true

            service.connect(token, username)

            // ЖДЕМ ПОДКЛЮЧЕНИЯ И ОТПРАВЛЯЕМ СТАТУС
            Handler(Looper.getMainLooper()).postDelayed({
                if (service.isConnected()) {
                    sendOnlineStatus(true)
                    Log.d(TAG, "✅ WebSocket connected and online status sent")
                } else {
                    Log.w(TAG, "⚠️ WebSocket not connected after delay, retrying...")
                    // Попробуем еще раз
                    Handler(Looper.getMainLooper()).postDelayed({
                        connectWebSocket()
                    }, 2000)
                }
                isWebSocketConnecting = false
            }, 3000)
        }
    }

    private fun startForegroundService() {
        Log.d(TAG, "📱 Creating notification channel...")

        // Проверяем разрешение на уведомления (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                Log.w(TAG, "⚠️ No notification permission on Android 13+")
            }
        }

        // 1. Создаем PendingIntent для уведомления
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 2. Создаем Notification channel (Android 8+)
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
            Log.d(TAG, "✅ Notification channel created with IMPORTANCE_DEFAULT")
        }

        // 3. Используем нашу иконку
        val iconId = try {
            R.drawable.app_icon
        } catch (e: Exception) {
            try {
                R.mipmap.ic_launcher
            } catch (e2: Exception) {
                android.R.drawable.ic_dialog_info
            }
        }

        // 4. Создаем уведомление
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

        // 1. Отключаем WebSocket
        WebSocketManager.disconnect()

        // 2. Освобождаем WakeLock
        releaseWakeLock()

        // 3. Останавливаем Foreground Service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        // 4. Останавливаем себя
        stopSelf()

        Log.d(TAG, "✅ Service stopped")
    }

    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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
                val service = WebSocketService.getInstance()
                if (!service.isConnected()) {
                    service.connect(token, username)
                    Log.d(TAG, "✅ WebSocket reconnection started")
                }
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

    private fun sendOnlineStatus(isOnline: Boolean) {
        val username = prefsManager.username
        if (!username.isNullOrEmpty()) {
            Log.d(TAG, "📤 Sending online status: $isOnline for $username")
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val userService = RetrofitClient.getClient().create(UserService::class.java)
                    val request = UserService.UpdateOnlineStatusRequest(username, isOnline)
                    userService.updateOnlineStatus(request)
                    Log.d(TAG, "✅ Online status updated: $isOnline")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error updating online status", e)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTokenChecker()
        ActivityCounter.removeListener { }

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
            // Для Android 8+ создаем канал
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

            // Минимальное уведомление
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

            // Запускаем foreground
            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "✅ Foreground started")

            // Скрываем уведомление
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
                tokenCheckHandler?.postDelayed(this, 30 * 60 * 1000L) // Каждые 30 минут
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

            // Отключаем старый WebSocket
            wsService.disconnect()

            Handler(Looper.getMainLooper()).postDelayed({
                wsService.connect(token, username)
                Log.d(TAG, "✅ WebSocket reconnected with new token")
            }, 1000)
        }
    }
}