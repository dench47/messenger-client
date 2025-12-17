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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.messenger.messengerclient.MainActivity
import com.messenger.messengerclient.R
import com.messenger.messengerclient.network.RetrofitClient
import com.messenger.messengerclient.utils.PrefsManager
import com.messenger.messengerclient.websocket.WebSocketManager
import com.messenger.messengerclient.websocket.WebSocketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.os.PowerManager
import com.messenger.messengerclient.utils.ActivityCounter


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

    private var minutesInBackground = 0
    private lateinit var backgroundTimerHandler: Handler

    private var activityHandler: Handler? = null
    private var activityRunnable: Runnable? = null

    private var isExplicitStop = false

    private var wakeLock: PowerManager.WakeLock? = null

    private var lastForegroundState: Boolean? = null

    private var tokenCheckHandler: Handler? = null
    private var tokenCheckRunnable: Runnable? = null




    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "✅ Service created")
        prefsManager = PrefsManager(this)
        backgroundTimerHandler = Handler(Looper.getMainLooper())

        acquireWakeLock()

        ActivityCounter.addListener { isForeground ->
            if (lastForegroundState == isForeground) {
                Log.d(TAG, "📱 ActivityCounter: Duplicate state ($isForeground), skipping")
                return@addListener
            }

            lastForegroundState = isForeground
            Log.d(TAG, "📱 ActivityCounter: app foreground = $isForeground")

            val intent = Intent(this@MessengerService, MessengerService::class.java)

            if (isForeground) {
                Log.d(TAG, "📱 App in FOREGROUND - sending ACTION_APP_FOREGROUND")
                intent.action = ACTION_APP_FOREGROUND
            } else {
                Log.d(TAG, "📱 App in BACKGROUND - sending ACTION_APP_BACKGROUND")
                intent.action = ACTION_APP_BACKGROUND
            }

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

        startTokenChecker() // ← ДОБАВИТЬ

        registerNetworkCallback()
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "MessengerService::WebSocketLock"
            )
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire(10 * 60 * 1000L) // 10 минут
            Log.d(TAG, "🔋 WakeLock ACQUIRED - WebSocket will stay alive")
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
            // Сервис перезапущен системой - ВОССТАНАВЛИВАЕМ всё
            Log.d(TAG, "⚡ Service restarted by system - restoring WakeLock and connection")

            // 1. Обновляем WakeLock
            acquireWakeLock()

            // 2. Восстанавливаем Foreground
            startForegroundService()

            // 3. Восстанавливаем WebSocket
            restoreService()

            return START_STICKY
        }

        when (intent.action) {
            ACTION_START -> {
                Log.d(TAG, "▶️ Starting foreground service with WakeLock")

                // 1. Активируем WakeLock ПЕРЕД запуском сервиса
                acquireWakeLock()

                // 2. Запускаем Foreground Service
                startForegroundService()

                // 3. Подключаем WebSocket
                connectWebSocket()

                // 4. Запускаем таймер активности
                startActivityTimer()
            }

            ACTION_STOP -> {
                Log.d(TAG, "⏹️ Stopping service (explicit) - releasing WakeLock")

                // 1. Помечаем что остановка явная
                isExplicitStop = true

                // 2. Освобождаем WakeLock
                releaseWakeLock()

                // 3. Останавливаем сервис
                stopService()

                return START_NOT_STICKY
            }

            ACTION_APP_BACKGROUND -> {
                Log.d(TAG, "📱 App went to BACKGROUND - stopping activity timer")

                // При переходе в background останавливаем activity timer
                // НО WakeLock и WebSocket остаются активными!
                stopActivityTimer()
                startBackgroundTimer()
            }

            ACTION_APP_FOREGROUND -> {
                Log.d(TAG, "📱 App returned to FOREGROUND - starting activity timer")

                // При возвращении в foreground
                stopBackgroundTimer()
                startActivityTimer()

                // Отправляем статус онлайн
                sendOnlineStatus(true)
            }

            else -> {
                Log.w(TAG, "⚠️ Unknown action: ${intent.action}")
            }
        }

        return START_STICKY
    }

    private fun restoreService() {
        Log.d(TAG, "🔄 Restoring service state")

        val token = prefsManager.authToken
        val username = prefsManager.username

        if (!token.isNullOrEmpty() && !username.isNullOrEmpty()) {
            // Восстанавливаем WebSocket
            val service = WebSocketService.getInstance()
            service.setContext(this)

            if (!service.isConnected()) {
                service.connect(token, username)
            }

            // Запускаем таймер активности
            startActivityTimer()

            Log.d(TAG, "✅ Service restored for user: $username")
        } else {
            Log.w(TAG, "⚠️ No credentials found")
        }
    }


    private fun startActivityTimer() {
        activityHandler = Handler(Looper.getMainLooper())
        activityRunnable = object : Runnable {
            override fun run() {
                sendActivityUpdateFromService()
                activityHandler?.postDelayed(this, 30000) // Каждые 30 секунд
            }
        }
        activityHandler?.post(activityRunnable!!)
        Log.d(TAG, "⏰ Activity timer started")
    }

    private fun stopActivityTimer() {
        activityHandler?.removeCallbacksAndMessages(null)
        activityRunnable = null
        Log.d(TAG, "⏰ Activity timer stopped")
    }

    private fun sendActivityUpdateFromService() {
        val username = prefsManager.username
        if (!username.isNullOrEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val userService = RetrofitClient.getClient().create(UserService::class.java)
                    val request = mapOf("username" to username)
                    userService.updateActivity(request)
                    Log.d(TAG, "✅ Activity updated from service for $username")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Activity update error", e)
                }
            }
        }
    }

    private fun startBackgroundTimer() {
        Log.d(TAG, "⏰ Starting background timer")
        minutesInBackground = 0
        backgroundTimerHandler.removeCallbacks(backgroundTimerRunnable)
        backgroundTimerHandler.postDelayed(backgroundTimerRunnable, 60000)
    }

    private fun stopBackgroundTimer() {
        Log.d(TAG, "⏰ Stopping background timer")
        backgroundTimerHandler.removeCallbacks(backgroundTimerRunnable)
        minutesInBackground = 0
    }

    private val backgroundTimerRunnable = object : Runnable {
        override fun run() {
            minutesInBackground++
            Log.d(TAG, "⏰ App in background for $minutesInBackground minute(s)")

            if (minutesInBackground >= 1
            ) {
                Log.d(TAG, "⏰ 1 minutes reached - updating last seen")
                updateLastSeenOnServer()
            }

            backgroundTimerHandler.postDelayed(this, 60000)
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
                // Можно продолжить без уведомления, но тогда не сможем быть foreground
                // Вместо этого попробуем создать уведомление anyway
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
                // Для Android 8.1+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setAllowBubbles(true)
                }
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            Log.d(TAG, "✅ Notification channel created with IMPORTANCE_DEFAULT")
        }

        // 3. Используем гарантированно существующую иконку
        val iconId = try {
            R.mipmap.ic_launcher
        } catch (e: Exception) {
            android.R.drawable.ic_dialog_info
        }
        Log.d(TAG, "🎨 Using icon ID: $iconId")

        // 4. Создаем уведомление
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Messenger - Активное соединение")
            .setContentText("Синхронизация сообщений и статусов")
            .setSmallIcon(iconId)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MAX) // ← МАКСИМАЛЬНЫЙ!
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(true)
            // Делаем уведомление "persistent"
            .setSilent(true) // Без звука
            .build()

        Log.d(TAG, "📋 Notification created, starting foreground...")
        try {
            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "✅ Service now in foreground")
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ SecurityException when starting foreground: ${e.message}")
            // Если не можем быть foreground, останавливаем Service
            stopSelf()
        }
    }

    private fun connectWebSocket() {
        val token = prefsManager.authToken
        val username = prefsManager.username

        if (!token.isNullOrEmpty() && !username.isNullOrEmpty()) {
            Log.d(TAG, "🔗 Connecting WebSocket from service")

            val service = WebSocketService.getInstance()
            service.setContext(this)

            if (!service.isConnected()) {
                service.connect(token, username)
                sendOnlineStatus(true)
            }
        }
    }

    private fun stopService() {
        Log.d(TAG, "🛑 Stopping service")

        // 1. Останавливаем таймеры
        stopActivityTimer()
        stopBackgroundTimer()

        // 2. Отключаем WebSocket
        WebSocketManager.disconnect()

        // 3. Освобождаем WakeLock (уже в ACTION_STOP, но на всякий случай)
        releaseWakeLock()

        // 4. Останавливаем Foreground Service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        // 5. Останавливаем себя
        stopSelf()

        Log.d(TAG, "✅ Service stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTokenChecker() // ← ДОБАВИТЬ

        ActivityCounter.removeListener { }

        Log.d(TAG, "💀 Service destroyed, isExplicitStop: $isExplicitStop")

        // 1. Освобождаем WakeLock (на всякий случай)
        if (!isExplicitStop) {
            Log.d(TAG, "⚠️ Service destroyed by system, releasing WakeLock")
            releaseWakeLock()
        }

        // 2. Отписываемся от network callback
        unregisterNetworkCallback()

        // 3. Останавливаем таймеры
        backgroundTimerHandler.removeCallbacks(backgroundTimerRunnable)
        activityHandler?.removeCallbacksAndMessages(null)

        // 4. Сервис может быть перезапущен системой (START_STICKY)
        Log.d(
            TAG, if (isExplicitStop) "🔚 Service stopped explicitly"
            else "🔄 Service may be restarted by system"
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "🗑️ App removed from recents - UPDATING LAST SEEN")
        ActivityCounter.reset() // ← СБРАСЫВАЕМ счетчик
        updateLastSeenOnServer()
        super.onTaskRemoved(rootIntent)
        // Сервис продолжит работать! Система перезапустит его если нужно.
    }

    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityManager =
                getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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

            // Скрываем уведомление (совместимо с API < 24)
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
        // Копируем логику из RetrofitClient.refreshToken()
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

            // Отключаем и подключаем заново
            wsService.disconnect()
            Handler(Looper.getMainLooper()).postDelayed({
                wsService.connect(token, username)
            }, 1000) // Задержка 1 сек
        }
    }

}