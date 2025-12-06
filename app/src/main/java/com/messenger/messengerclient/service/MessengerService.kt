package com.messenger.messengerclient.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.messenger.messengerclient.MainActivity
import com.messenger.messengerclient.R
import com.messenger.messengerclient.utils.PrefsManager
import com.messenger.messengerclient.websocket.WebSocketManager
import com.messenger.messengerclient.websocket.WebSocketService
import java.util.logging.Handler

class MessengerService : Service() {

    companion object {
        private const val TAG = "MessengerService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "messenger_service"
        const val ACTION_START = "start_service"
        const val ACTION_STOP = "stop_service"
    }

    private lateinit var prefsManager: PrefsManager
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null


    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "✅ Service created")
        prefsManager = PrefsManager(this)

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
                stopService()
                return START_NOT_STICKY
            }
            else -> {
                Log.w(TAG, "⚠️ Unknown action: ${intent.action}")
            }
        }

        return START_STICKY
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
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Вызывается когда приложение удаляется из Recent Apps
        Log.d(TAG, "🗑️ App removed from recents, stopping service")
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
            android.os.Handler(Looper.getMainLooper()).postDelayed({
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
    }    private fun unregisterNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && networkCallback != null) {
            connectivityManager?.unregisterNetworkCallback(networkCallback!!)
            networkCallback = null
            Log.d(TAG, "✅ Network callback unregistered")
        }
    }


    override fun onBind(intent: Intent?): IBinder? = null
}