package com.messenger.messengerclient.websocket

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
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

    // УПРОЩАЕМ: только 2 типа событий
    enum class UserEventType { CONNECTED, DISCONNECTED }

    data class UserEvent(
        val type: UserEventType,
        val username: String,
        val online: Boolean,
        val lastSeenText: String? = null,
        val status: String? = null
    )

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: WebSocketService? = null

        fun getInstance(): WebSocketService {
            return instance ?: synchronized(this) {
                instance ?: WebSocketService().also { instance = it }
            }
        }

        private const val TAG = "WebSocketService"
        private const val STOMP_HEARTBEAT = "0,0"

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
        private var lastOfferForCallActivity: Map<String, Any>? = null

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

    // 👇 НОВОЕ: Listener для статусов сообщений
    private var statusListener: ((Message) -> Unit)? = null

    // ДОБАВЛЯЕМ: флаг для предотвращения отправки UNSUBSCRIBE при отключении
    private var isDisconnecting = false

    fun setContext(context: Context) {
        this.context = context
        println("✅ [WebSocketService] Context set: ${context.packageName}")
    }

    fun setMessageListener(listener: (Message) -> Unit) {
        this.messageListener = listener
    }

    // 👇 НОВЫЙ МЕТОД: установка listener для статусов
    fun setStatusListener(listener: (Message) -> Unit) {
        this.statusListener = listener
    }

    // 👇 НОВЫЙ МЕТОД: отправка подтверждения статуса
    fun sendStatusConfirmation(messageId: Long, status: String, username: String): Boolean {
        if (!isStompConnected) {
            Log.e(TAG, "❌ Cannot send status: STOMP not connected")
            return false
        }

        return try {
            val statusUpdate = mapOf(
                "messageId" to messageId,
                "status" to status,
                "username" to username
            )

            val jsonMessage = gson.toJson(statusUpdate)

            val sendFrame = "SEND\n" +
                    "destination:/app/status\n" +
                    "content-type:application/json\n" +
                    "\n" +
                    jsonMessage +
                    "\u0000"

            webSocket?.send(sendFrame)
            Log.d(TAG, "📊 Status confirmation sent: messageId=$messageId, status=$status")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send status confirmation", e)
            false
        }
    }

    fun connect(token: String, username: String) {
        println("🔗 [WebSocketService] connect() called")

        // Сбрасываем флаг при подключении
        isDisconnecting = false

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
        val trimmedFrame = frame.trim()

        when {
            // 1. HEARTBEAT RabbitMQ - просто логируем, НЕ отвечаем!
            frame == "\n" || trimmedFrame.isEmpty() -> {
                Log.d(TAG, "❤️ RabbitMQ heartbeat received (ignoring)")
                // НЕ отправляем ответ! RabbitMQ сам управляет heartbeat
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

            // 3. CONNECTED (RabbitMQ)
            firstLine.startsWith("CONNECTED") -> {
                Log.d(TAG, "✅ RABBITMQ STOMP CONNECTED")
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

            // 6. USER EVENTS - УПРОЩЕННАЯ ЛОГИКА
            frame.contains("destination:/topic/user.events") -> {
                try {
                    Log.d(TAG, "👤 [DEBUG] Received user event")
                    val jsonStart = frame.indexOf('{')
                    val jsonEnd = frame.lastIndexOf('}')

                    if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                        val json = frame.substring(jsonStart, jsonEnd + 1)
                        Log.d(TAG, "👤 [DEBUG] User event JSON: $json")

                        val event = gson.fromJson(json, Map::class.java)
                        when (val eventType = event["type"] as? String) {
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
                                val isOnline = event["online"] as? Boolean ?: false
                                val lastSeenText = event["lastSeenText"] as? String ?: "offline"

                                Log.d(TAG, "👤 Simple user status: $username, online=$isOnline")

                                val eventType = if (isOnline) UserEventType.CONNECTED else UserEventType.DISCONNECTED

                                mainHandler.post {
                                    try {
                                        userEventListener?.invoke(
                                            UserEvent(
                                                type = eventType,
                                                username = username ?: "",
                                                online = isOnline,
                                                lastSeenText = lastSeenText,
                                                status = if (isOnline) "online" else "offline"
                                            )
                                        )
                                    } catch (e: Exception) {
                                        Log.e(TAG, "❌ Error in userEventListener", e)
                                    }
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

            // 9. 👇 НОВОЕ: STATUS UPDATES (для отправителя)
            frame.contains("destination:/user/queue/status") -> {
                try {
                    Log.d(TAG, "📊 [DEBUG] Received status update")

                    val jsonStart = frame.indexOf('{')
                    val jsonEnd = frame.lastIndexOf('}')

                    if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                        val json = frame.substring(jsonStart, jsonEnd + 1)
                        val message = gson.fromJson(json, Message::class.java)

                        mainHandler.post {
                            // Уведомляем слушателя статусов
                            statusListener?.invoke(message)
                            // Также уведомляем общего слушателя сообщений (для обновления UI)
                            messageListener?.invoke(message)
                            Log.d(TAG, "📊 Status updated for message ${message.id} to ${message.status}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ [DEBUG] Failed to parse status update", e)
                }
            }

            else -> {
                Log.d(TAG, "ℹ️ [DEBUG] Other STOMP frame: '$firstLine'")
            }
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

    fun disconnect() {
        // Устанавливаем флаг отключения
        isDisconnecting = true

        // Отправляем UNSUBSCRIBE только если мы еще подключены и не в процессе отключения

        webSocket?.close(1000, "Normal closure")
        webSocket = null
        messageListener = null
        onlineStatusListener = null
        userEventListener = null
        callSignalListener = null
        statusListener = null  // 👆 Очищаем новый listener
        username = null
        isStompConnected = false
        messageSubscriptionId = null
        onlineStatusSubscriptionId = null
        userEventsSubscriptionId = null

        // Сбрасываем флаг после завершения
        isDisconnecting = false
        Log.d(TAG, "🔌 WebSocket fully disconnected")
    }

    fun isConnected(): Boolean {
        return webSocket != null && isStompConnected
    }
}