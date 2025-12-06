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

class MessengerService : Service() {

    companion object {
        private const val TAG = "MessengerService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "messenger_service"
        const val ACTION_START = "start_service"
        const val ACTION_STOP = "stop_service"

        // НОВЫЕ: для определения foreground/background
        const val ACTION_APP_BACKGROUND = "app_background"
        const val ACTION_APP_FOREGROUND = "app_foreground"
    }

    private lateinit var prefsManager: PrefsManager
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var minutesInBackground = 0
    private lateinit var backgroundTimerHandler: Handler

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "✅ Service created")
        prefsManager = PrefsManager(this)

        // Инициализируем Handler
        backgroundTimerHandler = Handler(Looper.getMainLooper())

        // Регистрируем NetworkCallback
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "🔄 onStartCommand: ${intent?.action}")

        if (intent == null) {
            Log.e(TAG, "❌ Intent is null!")
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_START -> {
                Log.d(TAG, "▶️ Starting foreground service")
                startForegroundService()
                connectWebSocket()
            }
            ACTION_STOP -> {
                Log.d(TAG, "⏹️ Stopping service")
                stopBackgroundTimer()
                stopService()
                return START_NOT_STICKY
            }

            ACTION_APP_BACKGROUND -> {
                Log.d(TAG, "📱 App went to BACKGROUND - starting 5-minute timer")
                startBackgroundTimer()
            }

            ACTION_APP_FOREGROUND -> {
                Log.d(TAG, "📱 App returned to FOREGROUND - stopping timer")
                stopBackgroundTimer()
            }
            else -> {
                Log.w(TAG, "⚠️ Unknown action: ${intent.action}")
            }
        }

        return START_STICKY
    }

    private fun startBackgroundTimer() {
        Log.d(TAG, "⏰ Starting 5-minute background timer")
        minutesInBackground = 0
        backgroundTimerHandler.removeCallbacks(backgroundTimerRunnable) // Очищаем старые
        backgroundTimerHandler.postDelayed(backgroundTimerRunnable, 60000) // Первая проверка через 1 минуту
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

            if (minutesInBackground >= 5) {
                Log.d(TAG, "⏰ 5 minutes reached - updating last seen")
                updateLastSeenOnServer()
                // После обновления можно остановить или продолжать считать
                // stopBackgroundTimer() // или продолжаем считать дальше
            }

            // Продолжаем проверять каждую минуту
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
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Синхронизация сообщений"
                setShowBadge(true)
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
            .setContentTitle("Messenger")
            .setContentText("Соединение активно ✓")
            .setSmallIcon(iconId)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setAutoCancel(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
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

            // Получаем Singleton и устанавливаем context
            val service = WebSocketService.getInstance()
            service.setContext(this)  // ← ДОБАВИТЬ

            if (!service.isConnected()) {
                service.connect(token, username)
            }
        }
    }

    private fun stopService() {
        Log.d(TAG, "🛑 Stopping service")
        WebSocketManager.disconnect()

        // Исправленная строка:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        stopSelf()

        Log.d(TAG, "✅ Service stopped completely")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "💀 Service destroyed")

        // Отменяем NetworkCallback
        unregisterNetworkCallback()

        // Очищаем Handler
        backgroundTimerHandler.removeCallbacks(backgroundTimerRunnable)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "🗑️ App removed from recents - updating last seen immediately")
        updateLastSeenOnServer() // Немедленно обновляем last seen
        stopService()
        super.onTaskRemoved(rootIntent)
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
                    // Не отключаем сразу - heartbeat сам определит
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

            // Используем Handler для задержки
            Handler(Looper.getMainLooper()).postDelayed({
                // 1. Получаем Singleton WebSocketService
                val service = WebSocketService.getInstance()

                // 2. Если не подключен - подключаем
                if (!service.isConnected()) {
                    service.connect(token, username)
                    Log.d(TAG, "✅ WebSocket reconnection started")
                } else {
                    Log.d(TAG, "✅ WebSocket already connected")
                }
            }, 2000) // 2 секунды задержки
        } else {
            Log.w(TAG, "⚠️ Cannot reconnect: no token or username")
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
        val token = prefsManager.authToken
        val username = prefsManager.username

        if (!token.isNullOrEmpty() && !username.isNullOrEmpty()) {
            Log.d(TAG, "⏰ Updating last seen for $username (5+ minutes in background)")

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val userService = RetrofitClient.getClient().create(UserService::class.java)
                    val response = userService.updateLastSeen(username)

                    if (response.isSuccessful) {
                        Log.d(TAG, "✅ Last seen updated successfully")
                    } else {
                        Log.e(TAG, "❌ Failed to update last seen: ${response.code()}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error updating last seen", e)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}