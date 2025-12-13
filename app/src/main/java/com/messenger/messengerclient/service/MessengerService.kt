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

    private var activityHandler: Handler? = null
    private var activityRunnable: Runnable? = null

    private var isExplicitStop = false



    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "✅ Service created")
        prefsManager = PrefsManager(this)
        backgroundTimerHandler = Handler(Looper.getMainLooper())
        registerNetworkCallback()
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "🔄 onStartCommand: ${intent?.action}")

        if (intent == null) {
            // Сервис перезапущен системой
            Log.d(TAG, "⚡ Service restarted by system - restoring")
            startForegroundService()
            restoreService()
            return START_STICKY
        }

        when (intent.action) {
            ACTION_START -> {
                Log.d(TAG, "▶️ Starting foreground service")
                startForegroundService()
                connectWebSocket()
                startActivityTimer()
            }
            ACTION_STOP -> {
                Log.d(TAG, "⏹️ Stopping service (explicit)")
                isExplicitStop = true
                stopService()
                return START_NOT_STICKY
            }
            ACTION_APP_BACKGROUND -> {
                Log.d(TAG, "📱 App went to BACKGROUND - stopping activity timer")
                stopActivityTimer()
                startBackgroundTimer()
            }
            ACTION_APP_FOREGROUND -> {
                Log.d(TAG, "📱 App returned to FOREGROUND - starting activity timer")
                stopBackgroundTimer()
                startActivityTimer()
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

            if (minutesInBackground >= 5) {
                Log.d(TAG, "⏰ 5 minutes reached - updating last seen")
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

        stopActivityTimer()
        stopBackgroundTimer()
        WebSocketManager.disconnect()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        stopSelf()
        Log.d(TAG, "✅ Service stopped")
    }


    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "💀 Service destroyed, isExplicitStop: $isExplicitStop")

        unregisterNetworkCallback()
        backgroundTimerHandler.removeCallbacks(backgroundTimerRunnable)
        activityHandler?.removeCallbacksAndMessages(null)

        // Если сервис убит неявно (системой), система сама перезапустит его (START_STICKY)
    }


    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "🗑️ App removed from recents - UPDATING LAST SEEN")
        updateLastSeenOnServer()
        super.onTaskRemoved(rootIntent)
        // Сервис продолжит работать! Система перезапустит его если нужно.
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


    override fun onBind(intent: Intent?): IBinder? = null

}