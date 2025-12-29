package com.messenger.messengerclient.websocket

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.messenger.messengerclient.config.ApiConfig
import com.messenger.messengerclient.data.model.Message
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class WebSocketService {

    enum class UserEventType { CONNECTED, DISCONNECTED, INACTIVE }

    data class UserEvent(
        val type: UserEventType,
        val username: String,
        val online: Boolean,
        val lastSeenText: String? = null,
        val status: String? = null
    )

    companion object {
        @Volatile
        private var instance: WebSocketService? = null

        fun getInstance(): WebSocketService {
            return instance ?: synchronized(this) {
                instance ?: WebSocketService().also { instance = it }
            }
        }

        private const val TAG = "WebSocketService"
        private const val STOMP_HEARTBEAT = "10000,10000"

        private var statusUpdateCallback: ((List<String>) -> Unit)? = null

        fun setStatusUpdateCallback(callback: (List<String>) -> Unit) {
            println("✅ [WebSocketService] Static callback set")
            statusUpdateCallback = callback
        }

        fun clearStatusUpdateCallback() {
            statusUpdateCallback = null
        }

        // Статический user event listener
        private var staticUserEventListener: ((UserEvent) -> Unit)? = null

        fun setUserEventListener(listener: ((UserEvent) -> Unit)?) {
            // Устанавливаем оба listener-а
            getInstance().userEventListener = listener
            staticUserEventListener = listener
            Log.d(TAG, "✅ UserEventListener установлен: ${listener != null}")
        }

        // Статический call signal listener - для всех Activity
        private var staticCallSignalListener: ((Map<String, Any>) -> Unit)? = null

        // Отдельный listener ТОЛЬКО для CallActivity
        private var callActivitySignalListener: ((Map<String, Any>) -> Unit)? = null
        private var lastOfferForCallActivity: Map<String, Any>? = null  // ← НОВОЕ: сохраняем последний OFFER


        fun setCallSignalListener(listener: ((Map<String, Any>) -> Unit)?) {
            getInstance().callSignalListener = listener
            staticCallSignalListener = listener
            Log.d(TAG, "✅ CallSignalListener установлен: ${listener != null}")
        }

        // НОВЫЙ МЕТОД: ТОЛЬКО для CallActivity
        fun setCallSignalListenerForCallActivity(listener: ((Map<String, Any>) -> Unit)?) {
            callActivitySignalListener = listener

            // КРИТИЧЕСКОЕ ДОПОЛНЕНИЕ: если есть сохраненный OFFER, отправляем его сразу
            lastOfferForCallActivity?.let { offer ->
                Log.d(TAG, "📞 🔥 Delivering SAVED OFFER to CallActivity listener!")
                listener?.invoke(offer)
                lastOfferForCallActivity = null  // очищаем после отправки
            }

            Log.d(TAG, "📞 CallSignalListener для CallActivity: ${listener != null}")
        }
        fun clearCallSignalListenerForCallActivity() {
            callActivitySignalListener = null
            Log.d(TAG, "📞 CallSignalListener для CallActivity очищен")
        }

        // Внутренний метод для вызова всех слушателей
        private fun notifyCallSignalListeners(signal: Map<String, Any>) {
            val signalType = signal["type"] as? String

            // Если это OFFER, сохраняем его отдельно для CallActivity
            if (signalType == "offer") {
                Log.d(TAG, "📞 💾 Saving OFFER for CallActivity (listener might not be ready)")
                lastOfferForCallActivity = signal
            }

            // Вызываем listener MainActivity
            staticCallSignalListener?.invoke(signal)

            // Вызываем listener CallActivity (если установлен)
            callActivitySignalListener?.invoke(signal)
        }
    }

    private var webSocket: WebSocket? = null
    private val gson = Gson()
    private var messageListener: ((Message) -> Unit)? = null
    private var onlineStatusListener: ((List<String>) -> Unit)? = null
    private var username: String? = null
    private var isStompConnected = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var messageSubscriptionId: String? = null
    private var onlineStatusSubscriptionId: String? = null

    private var context: Context? = null

    private var userEventsSubscriptionId: String? = null
    private var userEventListener: ((UserEvent) -> Unit)? = null

    private var savedMessageListener: ((Message) -> Unit)? = null
    private var savedOnlineStatusListener: ((List<String>) -> Unit)? = null
    private var savedUserEventListener: ((UserEvent) -> Unit)? = null

    private var callSignalListener: ((Map<String, Any>) -> Unit)? = null

    fun setContext(context: Context) {
        this.context = context
        println("✅ [WebSocketService] Context set: ${context.packageName}")
    }

    fun setMessageListener(listener: (Message) -> Unit) {
        this.messageListener = listener
    }

    fun setOnlineStatusListener(listener: (List<String>) -> Unit) {
        this.onlineStatusListener = listener
    }

    fun connect(token: String, username: String) {
        println("🔗 [WebSocketService] connect() called")

        savedMessageListener = messageListener
        savedOnlineStatusListener = onlineStatusListener
        savedUserEventListener = userEventListener

        this.username = username
        disconnect()

        Log.d(TAG, "🔗 [DEBUG] Starting WebSocket connection for: $username")

        try {
            val client = OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()

            val request = Request.Builder()
                .url(ApiConfig.WS_BASE_URL)
                .addHeader("Authorization", "Bearer $token")
                .build()

            Log.d(TAG, "🔗 [DEBUG] Creating WebSocket...")
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "✅ [DEBUG] WebSocket transport layer CONNECTED for user: $username")
                    isStompConnected = false
                    sendStompConnect(token)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(
                        TAG,
                        "📩 STOMP raw (${text.length} chars): ${text.take(200)}"
                    )
                    processStompFrame(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "❌ WebSocket failure for $username: ${t.message}")
                    isStompConnected = false
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "🔌 WebSocket closed for user $username: $reason (code: $code)")
                    isStompConnected = false
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "💥 WebSocket connection error for user $username", e)
        }
    }

    private fun sendStompConnect(token: String) {
        val connectFrame = "CONNECT\n" +
                "accept-version:1.1,1.0\n" +
                "heart-beat:$STOMP_HEARTBEAT\n" +
                "Authorization:Bearer $token\n" +
                "\n" +
                "\u0000"

        webSocket?.send(connectFrame)
        Log.d(TAG, "📤 Sent STOMP CONNECT with Authorization header")
    }

    private fun sendSubscribe(destination: String, type: String = "message"): String {
        val subscriptionId = when (type) {
            "online" -> "sub-online-${System.currentTimeMillis()}"
            "user-events" -> "sub-user-events-${System.currentTimeMillis()}"
            "calls" -> "sub-calls-${System.currentTimeMillis()}"
            else -> "sub-msg-${System.currentTimeMillis()}"
        }

        val subscribeFrame = "SUBSCRIBE\n" +
                "id:$subscriptionId\n" +
                "destination:$destination\n" +
                "\n" +
                "\u0000"

        Log.d(TAG, "📤 SENDING SUBSCRIBE to: $destination (id: $subscriptionId)")
        webSocket?.send(subscribeFrame)

        return subscriptionId
    }

    private fun processStompFrame(frame: String) {
        val firstLine = frame.lines().firstOrNull() ?: ""

        when {
            // 1. HEARTBEAT
            frame == "\n" || frame.trim().isEmpty() -> {
                Log.d(TAG, "❤️ Heartbeat received, responding...")
                webSocket?.send("\n")
                return
            }

            // 2. ERROR
            firstLine.startsWith("ERROR") -> {
                Log.e(TAG, "❌ STOMP ERROR FRAME:")
                frame.lines().forEachIndexed { index, line ->
                    Log.e(TAG, "  [$index]: $line")
                }
                isStompConnected = false
            }

            // 3. CONNECTED
            firstLine.startsWith("CONNECTED") -> {
                Log.d(TAG, "✅ STOMP PROTOCOL CONNECTED")
                isStompConnected = true

                messageListener = savedMessageListener
                onlineStatusListener = savedOnlineStatusListener
                userEventListener = savedUserEventListener

                Log.d(TAG, "✅ Listeners restored")

                // Извлекаем username из фрейма
                var extractedUsername: String? = null
                frame.lines().forEach { line ->
                    if (line.startsWith("user-name:")) {
                        extractedUsername = line.substringAfter("user-name:").trim()
                    }
                }

                val userToSubscribe = extractedUsername ?: this.username

                if (userToSubscribe != null) {
                    messageSubscriptionId = sendSubscribe("/user/queue/messages", "message")
                    onlineStatusSubscriptionId = sendSubscribe("/topic/online.users", "online")
                    userEventsSubscriptionId = sendSubscribe("/topic/user.events", "user-events")
                    // ДОБАВЛЯЕМ подписку на звонки
                    sendSubscribe("/user/queue/calls", "calls")
                    Log.d(TAG, "✅ Все подписки установлены для: $userToSubscribe")
                } else {
                    Log.e(TAG, "❌ Cannot setup subscriptions: no username available!")
                }
            }

            // 4. CALL SIGNALS (обработка входящих звонков)
            frame.contains("destination:/user/queue/calls") -> {
                try {
                    Log.d(TAG, "📞 [DEBUG] Received call signal")
                    val jsonStart = frame.indexOf('{')
                    val jsonEnd = frame.lastIndexOf('}')

                    if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                        val json = frame.substring(jsonStart, jsonEnd + 1)
                        Log.d(TAG, "📞 [DEBUG] Call signal JSON: $json")

                        // Парсим как Map<String, Any>
                        val type = object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type
                        val callSignal: Map<String, Any> = gson.fromJson(json, type)

                        val signalType = callSignal["type"] as? String
                        Log.d(TAG, "📞 Signal type detected: $signalType")

                        mainHandler.post {
                            // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: используем новый метод для уведомления всех слушателей
                            notifyCallSignalListeners(callSignal)

                            // Также вызываем instance listener для обратной совместимости
                            this.callSignalListener?.invoke(callSignal)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ [DEBUG] Failed to parse call signal", e)
                }
            }

            // 5. ONLINE STATUS UPDATES
            frame.contains("destination:/topic/online.users") -> {
                try {
                    Log.d(TAG, "👥 Received online users update")
                    val jsonStart = frame.indexOf('[')
                    val jsonEnd = frame.lastIndexOf(']')

                    if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                        val json = frame.substring(jsonStart, jsonEnd + 1)
                        val onlineUsers = gson.fromJson(json, Array<String>::class.java).toList()

                        notifyOnlineStatusUpdate(onlineUsers)
                        mainHandler.post { onlineStatusListener?.invoke(onlineUsers) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ [DEBUG] Failed to parse online users", e)
                }
            }

            // 6. USER EVENTS
            frame.contains("destination:/topic/user.events") -> {
                try {
                    Log.d(TAG, "👤 [DEBUG] Received user event")
                    val jsonStart = frame.indexOf('{')
                    val jsonEnd = frame.lastIndexOf('}')

                    if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                        val json = frame.substring(jsonStart, jsonEnd + 1)
                        Log.d(TAG, "👤 [DEBUG] User event JSON: $json")

                        val event = gson.fromJson(json, Map::class.java)
                        val eventType = event["type"] as? String

                        when (eventType) {
                            "USER_DISCONNECTED" -> {
                                val username = event["username"] as? String
                                val lastSeenText = event["lastSeenText"] as? String
                                val isOnline = event["online"] as? Boolean ?: false

                                Log.d(TAG, "👤 User disconnected: $username, lastSeen: $lastSeenText")

                                mainHandler.post {
                                    userEventListener?.invoke(
                                        UserEvent(
                                            type = UserEventType.DISCONNECTED,
                                            username = username ?: "",
                                            online = isOnline,
                                            lastSeenText = lastSeenText
                                        )
                                    )
                                }
                            }

                            "USER_STATUS_UPDATE" -> {
                                val username = event["username"] as? String
                                val isOnline = event["online"] as? Boolean ?: true
                                val isActive = event["active"] as? Boolean ?: true
                                val status = event["status"] as? String ?: "active"
                                val lastSeenText = event["lastSeenText"] as? String

                                Log.d(TAG, "👤 User status update: $username, online=$isOnline")

                                val eventType = when {
                                    isOnline && isActive -> UserEventType.CONNECTED
                                    isOnline && !isActive -> UserEventType.INACTIVE
                                    else -> UserEventType.DISCONNECTED
                                }

                                val displayText = when {
                                    isOnline && isActive -> "online"
                                    isOnline && !isActive -> lastSeenText ?: "был недавно"
                                    else -> lastSeenText ?: "offline"
                                }

                                mainHandler.post {
                                    userEventListener?.invoke(
                                        UserEvent(
                                            type = eventType,
                                            username = username ?: "",
                                            online = isOnline,
                                            lastSeenText = displayText,
                                            status = status
                                        )
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ [DEBUG] Failed to parse user event", e)
                }
            }

            // 7. PERSONAL ONLINE STATUS
            frame.contains("destination:/user/queue/online.users") -> {
                try {
                    Log.d(TAG, "👤 [DEBUG] Received /user/queue/online.users (personal)")

                    val jsonStart = frame.indexOf('[')
                    val jsonEnd = frame.lastIndexOf(']')

                    if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                        val json = frame.substring(jsonStart, jsonEnd + 1)
                        val onlineUsers = gson.fromJson(json, Array<String>::class.java).toList()

                        mainHandler.post {
                            onlineStatusListener?.invoke(onlineUsers)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ [DEBUG] Failed to parse personal online users", e)
                }
            }

            // 8. PERSONAL MESSAGES
            frame.contains("destination:/user/queue/messages") -> {
                try {
                    Log.d(TAG, "📨 [DEBUG] Received personal message")

                    val jsonStart = frame.indexOf('{')
                    val jsonEnd = frame.lastIndexOf('}')

                    if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                        val json = frame.substring(jsonStart, jsonEnd + 1)
                        val message = gson.fromJson(json, Message::class.java)

                        mainHandler.post {
                            messageListener?.invoke(message)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ [DEBUG] Failed to parse personal message", e)
                }
            }

            else -> {
                Log.d(TAG, "ℹ️ [DEBUG] Other STOMP frame: '$firstLine'")
            }
        }
    }

    fun connectWithBatteryOptimization(token: String, username: String, isForeground: Boolean) {
        println("🔗 [WebSocketService] connectWithBatteryOptimization() - foreground: $isForeground")

        savedMessageListener = messageListener
        savedOnlineStatusListener = onlineStatusListener
        savedUserEventListener = userEventListener

        this.username = username
        disconnect()

        try {
            val client = if (!isForeground) {
                OkHttpClient.Builder()
                    .readTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .pingInterval(30, TimeUnit.SECONDS)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .build()
            } else {
                OkHttpClient.Builder()
                    .readTimeout(10, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build()
            }

            val request = Request.Builder()
                .url(ApiConfig.WS_BASE_URL)
                .addHeader("Authorization", "Bearer $token")
                .build()

            Log.d(TAG, "🔗 [DEBUG] Creating WebSocket (${if (isForeground) "foreground" else "background"} mode)...")

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "✅ [DEBUG] WebSocket ${if (isForeground) "foreground" else "background"} CONNECTED for: $username")
                    isStompConnected = false
                    sendStompConnect(token)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "📩 STOMP raw (${if (isForeground) "FG" else "BG"}): ${text.take(50)}...")
                    processStompFrame(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "❌ WebSocket ${if (isForeground) "foreground" else "background"} failure: ${t.message}")
                    isStompConnected = false
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "🔌 WebSocket ${if (isForeground) "foreground" else "background"} closed: $reason")
                    isStompConnected = false
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "💥 WebSocket connection error in ${if (isForeground) "foreground" else "background"} mode", e)
        }
    }

    private fun notifyOnlineStatusUpdate(onlineUsers: List<String>) {
        println("📡 [WebSocketService] Notifying status update: $onlineUsers")

        statusUpdateCallback?.let { callback ->
            println("   ✅ Static callback exists, calling...")
            try {
                Handler(Looper.getMainLooper()).post {
                    callback(onlineUsers)
                }
            } catch (e: Exception) {
                println("   ❌ Error in static callback: ${e.message}")
            }
        } ?: run {
            println("   ⚠️ No static callback set")
        }
    }

    fun sendMessage(message: Message): Boolean {
        if (!isStompConnected) {
            Log.e(TAG, "❌ Cannot send: STOMP not connected")
            return false
        }

        return try {
            val messageToSend = message.copy(id = null)
            val jsonMessage = gson.toJson(messageToSend)

            val sendFrame = "SEND\n" +
                    "destination:/app/chat\n" +
                    "content-type:application/json\n" +
                    "\n" +
                    jsonMessage +
                    "\u0000"

            webSocket?.send(sendFrame)
            Log.d(TAG, "📤 STOMP SEND to /app/chat: ${message.content}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send via STOMP", e)
            false
        }
    }

    // Метод для отправки call сигналов
    fun sendCallSignal(callSignal: Map<String, Any>): Boolean {
        if (!isStompConnected) {
            Log.e(TAG, "❌ Cannot send call signal: STOMP not connected")
            return false
        }

        return try {
            val jsonMessage = gson.toJson(callSignal)

            val sendFrame = "SEND\n" +
                    "destination:/app/call\n" +
                    "content-type:application/json\n" +
                    "\n" +
                    jsonMessage +
                    "\u0000"

            webSocket?.send(sendFrame)
            Log.d(TAG, "📤 STOMP SEND to /app/call: ${callSignal["type"]}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send call signal via STOMP", e)
            false
        }
    }

    // Метод для отправки raw фреймов (для обратной совместимости)
    fun sendRawFrame(frame: String): Boolean {
        return try {
            webSocket?.send(frame)
            Log.d(TAG, "📤 Raw frame sent: ${frame.take(100)}...")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send raw frame", e)
            false
        }
    }

    fun disconnect() {
        // Отправляем UNSUBSCRIBE для всех подписок
        messageSubscriptionId?.let { id ->
            val unsubscribeFrame = "UNSUBSCRIBE\nid:$id\n\n\u0000"
            webSocket?.send(unsubscribeFrame)
            Log.d(TAG, "📤 Sent UNSUBSCRIBE for messages (id: $id)")
        }

        onlineStatusSubscriptionId?.let { id ->
            val unsubscribeFrame = "UNSUBSCRIBE\nid:$id\n\n\u0000"
            webSocket?.send(unsubscribeFrame)
            Log.d(TAG, "📤 Sent UNSUBSCRIBE for online status (id: $id)")
        }

        userEventsSubscriptionId?.let { id ->
            val unsubscribeFrame = "UNSUBSCRIBE\nid:$id\n\n\u0000"
            webSocket?.send(unsubscribeFrame)
            Log.d(TAG, "📤 Sent UNSUBSCRIBE for user events (id: $id)")
        }

        // Отправляем DISCONNECT если подключены
        if (isStompConnected) {
            val disconnectFrame = "DISCONNECT\n\n\u0000"
            webSocket?.send(disconnectFrame)
        }

        webSocket?.close(1000, "Normal closure")
        webSocket = null
        messageListener = null
        onlineStatusListener = null
        userEventListener = null
        callSignalListener = null
        username = null
        isStompConnected = false
        messageSubscriptionId = null
        onlineStatusSubscriptionId = null
        userEventsSubscriptionId = null
        Log.d(TAG, "🔌 WebSocket fully disconnected")
    }

    fun isConnected(): Boolean {
        return webSocket != null && isStompConnected
    }
}