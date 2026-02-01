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

        // Адаптивные интервалы (в миллисекундах)
        private const val INTERVAL_FOREGROUND_ACTIVITY = 30 * 1000L       // 30 сек
        private const val INTERVAL_FOREGROUND_RECONNECT = 60 * 1000L      // 1 мин
        private const val INTERVAL_BACKGROUND_SHORT_ACTIVITY = 2 * 60 * 1000L  // 2 мин
        private const val INTERVAL_BACKGROUND_SHORT_RECONNECT = 5 * 60 * 1000L // 5 мин
        private const val INTERVAL_BACKGROUND_LONG_ACTIVITY = 5 * 60 * 1000L   // 5 мин
        private const val INTERVAL_BACKGROUND_LONG_RECONNECT = 10 * 60 * 1000L // 10 мин

        private const val BACKGROUND_SHORT_THRESHOLD = 15 // минут
    }

    private lateinit var prefsManager: PrefsManager
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var minutesInBackground = 0
    private lateinit var backgroundTimerHandler: Handler

    // Адаптивные таймеры
    private var adaptiveActivityHandler: Handler? = null
    private var adaptiveActivityRunnable: Runnable? = null
    private var adaptiveReconnectHandler: Handler? = null
    private var adaptiveReconnectRunnable: Runnable? = null

    private var isExplicitStop = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastForegroundState: Boolean? = null
    private var tokenCheckHandler: Handler? = null
    private var tokenCheckRunnable: Runnable? = null



    // Текущий режим
    private enum class BatteryMode {
        FOREGROUND,
        BACKGROUND_SHORT,    // < 15 мин в фоне
        BACKGROUND_LONG,     // > 15 мин в фоне
        DOZE                 // Doze режим
    }

    private var currentBatteryMode = BatteryMode.FOREGROUND

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "✅ Service created")
        prefsManager = PrefsManager(this)
        backgroundTimerHandler = Handler(Looper.getMainLooper())

        // Инициализируем режим
        currentBatteryMode = if (ActivityCounter.isAppInForeground()) {
            BatteryMode.FOREGROUND
        } else {
            BatteryMode.BACKGROUND_SHORT
        }

        acquireSmartWakeLock()

        ActivityCounter.clearListeners()


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

        startTokenChecker()
        registerNetworkCallback()
    }

    private fun acquireSmartWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Messenger::SmartLock"
            )

            // Release через 1 минуту в фоне
            wakeLock?.setReferenceCounted(false)

            // Обновляем при возвращении в foreground
            if (ActivityCounter.isAppInForeground()) {
                wakeLock?.acquire(5 * 60 * 1000L) // 5 минут в foreground
                Log.d(TAG, "🔋 Smart WakeLock ACQUIRED for 5 min (foreground)")
            } else {
                wakeLock?.acquire(1 * 60 * 1000L) // 1 минута в фоне
                Log.d(TAG, "🔋 Smart WakeLock ACQUIRED for 1 min (background)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun updateWakeLockForMode() {
        when (currentBatteryMode) {
            BatteryMode.FOREGROUND -> {
                wakeLock?.acquire(5 * 60 * 1000L) // 5 минут
                Log.d(TAG, "🔋 WakeLock updated: 5 min (foreground)")
            }
            BatteryMode.BACKGROUND_SHORT -> {
                wakeLock?.acquire(1 * 60 * 1000L) // 1 минута
                Log.d(TAG, "🔋 WakeLock updated: 1 min (background short)")
            }
            BatteryMode.BACKGROUND_LONG -> {
                wakeLock?.acquire(30 * 1000L) // 30 секунд
                Log.d(TAG, "🔋 WakeLock updated: 30 sec (background long)")
            }
            BatteryMode.DOZE -> {
                // Doze режим - не держим WakeLock постоянно
                wakeLock?.acquire(10 * 1000L) // 10 секунд для операции
                Log.d(TAG, "🔋 WakeLock updated: 10 sec (doze)")
            }
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

            acquireSmartWakeLock()
            startForegroundService()
            restoreService()

            return START_STICKY
        }

        when (intent.action) {
            ACTION_START -> {
                Log.d(TAG, "▶️ Starting foreground service with Smart WakeLock")

                acquireSmartWakeLock()
                startForegroundService()
                connectWebSocket()
                startAdaptiveTimers()
            }

            ACTION_STOP -> {
                Log.d(TAG, "⏹️ Stopping service (explicit) - releasing WakeLock")

                isExplicitStop = true
                stopAdaptiveTimers()
                releaseWakeLock()
                stopService()

                return START_NOT_STICKY
            }

            ACTION_APP_BACKGROUND -> {
                Log.d(TAG, "📱 App went to BACKGROUND - DOING SWIPE LOGIC")

                // ТОЧНО ТАК ЖЕ КАК В onTaskRemoved() ПРИ СВАЙПЕ:
                updateLastSeenOnServer()  // ← ЭТА СТРОКА УЖЕ ЕСТЬ У ВАС!

                WebSocketManager.disconnect()

                // Останавливаем таймеры (если были запущены в foreground)
                stopAdaptiveTimers()
                stopBackgroundTimer()

                // ВСЁ! Больше ничего не делаем!
                // currentBatteryMode = BatteryMode.BACKGROUND_SHORT ← УБРАТЬ!
                // minutesInBackground = 0 ← УБРАТЬ!
                // startAdaptiveTimers() ← УБРАТЬ!
                // updateWakeLockForMode() ← УБРАТЬ!
                // startBackgroundTimer() ← УБРАТЬ!

                // Сервис будет висеть до убийства системой (как при свайпе)
            }


            ACTION_APP_FOREGROUND -> {
                Log.d(TAG, "📱 App returned to FOREGROUND - switching to foreground mode")

                currentBatteryMode = BatteryMode.FOREGROUND
                stopBackgroundTimer()
//                stopAdaptiveTimers()
                startAdaptiveTimers()
                updateWakeLockForMode()

//                sendOnlineStatus(true)
//                sendActivityUpdateFromService() // Немедленная активность
            }

            else -> {
                Log.w(TAG, "⚠️ Unknown action: ${intent.action}")
            }
        }

        return START_STICKY
    }

    private fun startAdaptiveTimers() {
        stopAdaptiveTimers()

        val (activityInterval, reconnectInterval) = when (currentBatteryMode) {
            BatteryMode.FOREGROUND -> Pair(INTERVAL_FOREGROUND_ACTIVITY, INTERVAL_FOREGROUND_RECONNECT)
            BatteryMode.BACKGROUND_SHORT -> Pair(INTERVAL_BACKGROUND_SHORT_ACTIVITY, INTERVAL_BACKGROUND_SHORT_RECONNECT)
            BatteryMode.BACKGROUND_LONG -> Pair(INTERVAL_BACKGROUND_LONG_ACTIVITY, INTERVAL_BACKGROUND_LONG_RECONNECT)
            BatteryMode.DOZE -> Pair(0L, 0L) // Doze - только при пробуждении
        }

        // Activity timer (обновление активности)
        if (activityInterval > 0) {
            adaptiveActivityHandler = Handler(Looper.getMainLooper())
            adaptiveActivityRunnable = object : Runnable {
                override fun run() {
                    sendActivityUpdateFromService()
                    adaptiveActivityHandler?.postDelayed(this, activityInterval)
                    Log.d(TAG, "⏰ Activity timer tick ($currentBatteryMode, ${activityInterval/1000}s)")
                }
            }
            adaptiveActivityHandler?.post(adaptiveActivityRunnable!!)
        }

        // Reconnect timer (проверка соединения)
        if (reconnectInterval > 0) {
            adaptiveReconnectHandler = Handler(Looper.getMainLooper())
            adaptiveReconnectRunnable = object : Runnable {
                override fun run() {
                    checkAndReconnectWebSocket()
                    adaptiveReconnectHandler?.postDelayed(this, reconnectInterval)
                    Log.d(TAG, "🔄 Reconnect timer tick ($currentBatteryMode, ${reconnectInterval/1000}s)")
                }
            }
            adaptiveReconnectHandler?.post(adaptiveReconnectRunnable!!)
        }

        Log.d(TAG, "⏰ Adaptive timers started: $currentBatteryMode")
        Log.d(TAG, "   Activity: ${activityInterval/1000}s, Reconnect: ${reconnectInterval/1000}s")
    }

    private fun stopAdaptiveTimers() {
        adaptiveActivityHandler?.removeCallbacksAndMessages(null)
        adaptiveReconnectHandler?.removeCallbacksAndMessages(null)
        adaptiveActivityRunnable = null
        adaptiveReconnectRunnable = null
        Log.d(TAG, "⏰ Adaptive timers stopped")
    }

    private fun checkAndReconnectWebSocket() {
        val service = WebSocketService.getInstance()
        if (!service.isConnected()) {
            Log.d(TAG, "🔗 WebSocket not connected, attempting reconnect")
            reconnectWebSocket()
        } else {
            Log.d(TAG, "🔗 WebSocket connection healthy")
        }
    }

    private fun sendActivityUpdateFromService() {
        val username = prefsManager.username
        if (!username.isNullOrEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val userService = RetrofitClient.getClient().create(UserService::class.java)
                    val request = mapOf("username" to username)
                    userService.updateActivity(request)
                    Log.d(TAG, "✅ Activity updated for $username ($currentBatteryMode)")
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

            // Переключаем режимы по времени
            when {
                minutesInBackground >= BACKGROUND_SHORT_THRESHOLD && currentBatteryMode == BatteryMode.BACKGROUND_SHORT -> {
                    currentBatteryMode = BatteryMode.BACKGROUND_LONG
//                    stopAdaptiveTimers()
                    startAdaptiveTimers()
                    updateWakeLockForMode()
                    Log.d(TAG, "⚡ Switching to BACKGROUND_LONG mode (15+ min)")
                }
                minutesInBackground == 1 -> {
                    // Через 1 минуту в фоне обновляем last seen
                    updateLastSeenOnServer()
                }
            }

            backgroundTimerHandler.postDelayed(this, 60000)
        }
    }

    private fun restoreService() {
        Log.d(TAG, "🔄 Restoring service state")

        val token = prefsManager.authToken
        val username = prefsManager.username

        if (!token.isNullOrEmpty() && !username.isNullOrEmpty()) {
            val service = WebSocketService.getInstance()
            service.setContext(this)

            if (!service.isConnected()) {
                val isForeground = ActivityCounter.isAppInForeground()
                service.connectWithBatteryOptimization(token, username, isForeground)
            }

            // Определяем текущий режим и запускаем таймеры
            currentBatteryMode = if (ActivityCounter.isAppInForeground()) {
                BatteryMode.FOREGROUND
            } else {
                BatteryMode.BACKGROUND_SHORT
            }
            startAdaptiveTimers()

            Log.d(TAG, "✅ Service restored for user: $username ($currentBatteryMode)")
        } else {
            Log.w(TAG, "⚠️ No credentials found")
        }
    }

    private fun connectWebSocket() {
        val token = prefsManager.authToken
        val username = prefsManager.username

        if (!token.isNullOrEmpty() && !username.isNullOrEmpty()) {
            Log.d(TAG, "🔗 Connecting WebSocket from service (mode: $currentBatteryMode)")

            val service = WebSocketService.getInstance()
            service.setContext(this)

            if (!service.isConnected()) {
                val isForeground = currentBatteryMode == BatteryMode.FOREGROUND
                service.connectWithBatteryOptimization(token, username, isForeground)
                sendOnlineStatus(true)
            }
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
                R.mipmap.ic_launcher // fallback на системную
            } catch (e2: Exception) {
                android.R.drawable.ic_dialog_info // ultimate fallback
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

        // 1. Останавливаем таймеры
        stopAdaptiveTimers()
        stopBackgroundTimer()

        // 2. Отключаем WebSocket
        WebSocketManager.disconnect()

        // 3. Освобождаем WakeLock
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
            Log.d(TAG, "🔗 Attempting WebSocket reconnection for $username (mode: $currentBatteryMode)")
            Handler(Looper.getMainLooper()).postDelayed({
                val service = WebSocketService.getInstance()
                if (!service.isConnected()) {
                    val isForeground = currentBatteryMode == BatteryMode.FOREGROUND
                    service.connectWithBatteryOptimization(token, username, isForeground)
                    Log.d(TAG, "✅ WebSocket reconnection started (${if (isForeground) "foreground" else "background"})")
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
        stopAdaptiveTimers()
        backgroundTimerHandler.removeCallbacks(backgroundTimerRunnable)

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
            Log.d(TAG, "🔗 Reconnecting WebSocket with new token (mode: $currentBatteryMode)")
            val wsService = WebSocketService.getInstance()

            // Отключаем старый WebSocket
            wsService.disconnect()

            Handler(Looper.getMainLooper()).postDelayed({
                // Используем оптимизированное подключение
                val isForeground = currentBatteryMode == BatteryMode.FOREGROUND
                wsService.connectWithBatteryOptimization(token, username, isForeground)
                Log.d(TAG, "✅ WebSocket reconnected with new token (${if (isForeground) "foreground" else "background"})")
            }, 1000) // Задержка 1 сек
        }
    }
}