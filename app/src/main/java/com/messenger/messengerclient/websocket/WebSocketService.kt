package com.messenger.messengerclient.websocket

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.messenger.messengerclient.config.ApiConfig
import com.messenger.messengerclient.data.model.Message
import com.messenger.messengerclient.data.model.MessageStatusBatchUpdateDto
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

class WebSocketService {

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

        private var staticUserEventListener: ((UserEvent) -> Unit)? = null

        fun setUserEventListener(listener: ((UserEvent) -> Unit)?) {
            getInstance().userEventListener = listener
            staticUserEventListener = listener
            Log.d(TAG, "✅ UserEventListener установлен: ${listener != null}")
        }

        private var staticCallSignalListener: ((Map<String, Any>) -> Unit)? = null
        private var callActivitySignalListener: ((Map<String, Any>) -> Unit)? = null
        private var lastOfferForCallActivity: Map<String, Any>? = null

        fun setCallSignalListenerForCallActivity(listener: ((Map<String, Any>) -> Unit)?) {
            callActivitySignalListener = listener
            lastOfferForCallActivity?.let { offer ->
                Log.d(TAG, "📞 🔥 Delivering SAVED OFFER to CallActivity listener!")
                listener?.invoke(offer)
                lastOfferForCallActivity = null
            }
            Log.d(TAG, "📞 CallSignalListener для CallActivity: ${listener != null}")
        }

        fun clearCallSignalListenerForCallActivity() {
            callActivitySignalListener = null
            Log.d(TAG, "📞 CallSignalListener для CallActivity очищен")
        }

        private fun notifyCallSignalListeners(signal: Map<String, Any>) {
            val signalType = signal["type"] as? String
            if (signalType == "offer") {
                Log.d(TAG, "📞 💾 Saving OFFER for CallActivity")
                lastOfferForCallActivity = signal
            }
            staticCallSignalListener?.invoke(signal)
            callActivitySignalListener?.invoke(signal)
        }
    }

    private var webSocket: WebSocket? = null
    private val gson = Gson()
    // 👇 ИЗМЕНЕНО: список слушателей сообщений
    private val messageListeners = CopyOnWriteArrayList<(Message) -> Unit>()
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

    private val statusListeners = CopyOnWriteArrayList<(Message) -> Unit>()

    private var isDisconnecting = false

    fun setContext(context: Context) {
        this.context = context
        println("✅ [WebSocketService] Context set: ${context.packageName}")
    }

    // 👇 НОВЫЕ МЕТОДЫ ДЛЯ РАБОТЫ СО СПИСКОМ СЛУШАТЕЛЕЙ СООБЩЕНИЙ
    fun addMessageListener(listener: (Message) -> Unit) {
        messageListeners.add(listener)
        Log.d(TAG, "✅ Message listener added, total: ${messageListeners.size}")
    }

    fun removeMessageListener(listener: (Message) -> Unit) {
        messageListeners.remove(listener)
        Log.d(TAG, "✅ Message listener removed, total: ${messageListeners.size}")
    }

    fun clearMessageListeners() {
        messageListeners.clear()
        Log.d(TAG, "✅ All message listeners cleared")
    }

    fun getMessageListenersCount(): Int = messageListeners.size

    // 👇 для обратной совместимости
    fun setMessageListener(listener: (Message) -> Unit) {
        messageListeners.clear()
        messageListeners.add(listener)
        Log.d(TAG, "✅ Message listener set (single), total: ${messageListeners.size}")
    }

    // 👇 МЕТОДЫ ДЛЯ СТАТУСОВ
    fun addStatusListener(listener: (Message) -> Unit) {
        statusListeners.add(listener)
        Log.d(TAG, "✅ Status listener added, total: ${statusListeners.size}")
    }

    fun removeStatusListener(listener: (Message) -> Unit) {
        statusListeners.remove(listener)
        Log.d(TAG, "✅ Status listener removed, total: ${statusListeners.size}")
    }

    fun getStatusListenersCount(): Int {
        return statusListeners.size
    }

    fun notifyStatusListeners(message: Message) {
        Log.d(TAG, "📢 Notifying ${statusListeners.size} status listeners")
        statusListeners.forEach { it.invoke(message) }
    }

    fun setStatusListener(listener: (Message) -> Unit) {
        statusListeners.clear()
        statusListeners.add(listener)
        Log.d(TAG, "✅ Status listener set (single), total: ${statusListeners.size}")
    }

    fun clearStatusListeners() {
        statusListeners.clear()
        Log.d(TAG, "✅ All status listeners cleared")
    }

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

    fun sendBatchStatusConfirmation(messageIds: List<Long>, status: String, username: String): Boolean {
        if (!isStompConnected) {
            Log.e(TAG, "❌ Cannot send batch status: STOMP not connected")
            return false
        }

        if (messageIds.isEmpty()) {
            Log.d(TAG, "📊 No messages to update")
            return true
        }

        return try {
            val batchUpdate = MessageStatusBatchUpdateDto(
                messageIds = messageIds,
                status = status,
                username = username
            )

            val jsonMessage = gson.toJson(batchUpdate)

            val sendFrame = "SEND\n" +
                    "destination:/app/status/batch\n" +
                    "content-type:application/json\n" +
                    "\n" +
                    jsonMessage +
                    "\u0000"

            webSocket?.send(sendFrame)
            Log.d(TAG, "📊 BATCH status confirmation sent: ${messageIds.size} messages, status=$status")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send batch status confirmation", e)
            false
        }
    }

    fun connect(token: String, username: String) {
        println("🔗 [WebSocketService] connect() called")

        isDisconnecting = false
        // 👇 Сохраняем ссылку на первый слушатель для совместимости
        savedMessageListener = if (messageListeners.isNotEmpty()) messageListeners[0] else null
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
                    Log.d(TAG, "📩 STOMP raw (${text.length} chars): ${text.take(200)}")
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
        Log.d(TAG, "📩 FULL FRAME: $frame")

        val firstLine = frame.lines().firstOrNull() ?: ""
        val trimmedFrame = frame.trim()

        when {
            frame == "\n" || trimmedFrame.isEmpty() -> {
                Log.d(TAG, "❤️ RabbitMQ heartbeat received (ignoring)")
                return
            }

            firstLine.startsWith("ERROR") -> {
                Log.e(TAG, "❌ STOMP ERROR FRAME:")
                frame.lines().forEachIndexed { index, line ->
                    Log.e(TAG, "  [$index]: $line")
                }
                isStompConnected = false
            }

            firstLine.startsWith("CONNECTED") -> {
                Log.d(TAG, "✅ RABBITMQ STOMP CONNECTED")
                isStompConnected = true

                // 👇 Восстанавливаем слушатели
                if (savedMessageListener != null) {
                    messageListeners.clear()
                    messageListeners.add(savedMessageListener!!)
                }
                onlineStatusListener = savedOnlineStatusListener
                userEventListener = savedUserEventListener

                Log.d(TAG, "✅ Listeners restored")

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
                    sendSubscribe("/user/queue/status", "status")
                    Log.d(TAG, "✅ Все подписки установлены для: $userToSubscribe")
                } else {
                    Log.e(TAG, "❌ Cannot setup subscriptions: no username available!")
                }
            }

            frame.contains("destination:/user/queue/calls") -> {
                try {
                    Log.d(TAG, "📞 [DEBUG] Received call signal")
                    val jsonStart = frame.indexOf('{')
                    val jsonEnd = frame.lastIndexOf('}')

                    if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                        val json = frame.substring(jsonStart, jsonEnd + 1)
                        Log.d(TAG, "📞 [DEBUG] Call signal JSON: $json")

                        val type = object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type
                        val callSignal: Map<String, Any> = gson.fromJson(json, type)

                        val signalType = callSignal["type"] as? String
                        Log.d(TAG, "📞 Signal type detected: $signalType")

                        mainHandler.post {
                            notifyCallSignalListeners(callSignal)
                            this.callSignalListener?.invoke(callSignal)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ [DEBUG] Failed to parse call signal", e)
                }
            }

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

            // 👇 ИСПРАВЛЕННЫЙ ОБРАБОТЧИК СООБЩЕНИЙ
            frame.contains("destination:/user/queue/messages") -> {
                try {
                    Log.d(TAG, "📨 [DEBUG] Received personal message")

                    val jsonStart = frame.indexOf('{')
                    val jsonEnd = frame.lastIndexOf('}')

                    if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                        val json = frame.substring(jsonStart, jsonEnd + 1)
                        val message = gson.fromJson(json, Message::class.java)
                        Log.d(TAG, "📨 MESSAGE RECEIVED IN WEBSOCKET: ${message.id}")

                        mainHandler.post {
                            // 👇 ВЫЗЫВАЕМ ВСЕХ СЛУШАТЕЛЕЙ СООБЩЕНИЙ
                            messageListeners.forEach { it.invoke(message) }
                            Log.d(TAG, "📨 Notified ${messageListeners.size} listeners")  // 👈 ДОБАВЬ ЭТОТ ЛОГ!

                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ [DEBUG] Failed to parse personal message", e)
                }
            }

            frame.contains("destination:/user/queue/status") -> {
                try {
                    Log.d(TAG, "📊 [DEBUG] Received status update frame")

                    val firstChar = frame.indexOfFirst { it == '{' || it == '[' }
                    val jsonStart = if (firstChar >= 0) firstChar else -1
                    val jsonEnd = frame.lastIndexOf(if (frame.contains('[')) ']' else '}')

                    if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                        val json = frame.substring(jsonStart, jsonEnd + 1)
                        Log.d(TAG, "📊 [DEBUG] Status JSON: $json")

                        if (json.startsWith("[")) {
                            val type = object : com.google.gson.reflect.TypeToken<List<Message>>() {}.type
                            val messages: List<Message> = gson.fromJson(json, type)

                            Log.d(TAG, "📊 [BATCH] Parsed ${messages.size} messages with status updates")

                            mainHandler.post {
                                messages.forEach { message ->
                                    statusListeners.forEach { it.invoke(message) }
                                }
                                Log.d(TAG, "📊 [BATCH] Processed ${messages.size} status updates to ${statusListeners.size} listeners")
                            }
                        } else {
                            val message = gson.fromJson(json, Message::class.java)
                            Log.d(TAG, "📊 [DEBUG] Parsed message: id=${message.id}, status=${message.status}")

                            mainHandler.post {
                                statusListeners.forEach { it.invoke(message) }
                                // 👇 ТАКЖЕ УВЕДОМЛЯЕМ СЛУШАТЕЛЕЙ СООБЩЕНИЙ О СТАТУСАХ
                                messageListeners.forEach { it.invoke(message) }
                                Log.d(TAG, "📊 Status updated for message ${message.id} to ${message.status}")
                            }
                        }
                    } else {
                        Log.e(TAG, "❌ [DEBUG] No JSON found in frame")
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

    fun sendUserActivity(activityData: Map<String, Any?>): Boolean {
        if (!isStompConnected) {
            Log.e(TAG, "❌ Cannot send activity: STOMP not connected")
            return false
        }

        return try {
            val jsonMessage = gson.toJson(activityData)

            val sendFrame = "SEND\n" +
                    "destination:/app/user/activity\n" +
                    "content-type:application/json\n" +
                    "\n" +
                    jsonMessage +
                    "\u0000"

            webSocket?.send(sendFrame)
            Log.d(TAG, "👤 User activity sent: $activityData")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send user activity", e)
            false
        }
    }

    fun hasMessageListener(listener: (Message) -> Unit): Boolean {
        return messageListeners.contains(listener)
    }

    fun hasStatusListener(listener: (Message) -> Unit): Boolean {
        return statusListeners.contains(listener)
    }

    fun disconnect() {
        isDisconnecting = true
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        // 👇 НЕ ОЧИЩАЕМ СПИСКИ СЛУШАТЕЛЕЙ
        username = null
        isStompConnected = false
        messageSubscriptionId = null
        onlineStatusSubscriptionId = null
        userEventsSubscriptionId = null
        isDisconnecting = false
        Log.d(TAG, "🔌 WebSocket fully disconnected")
    }

    fun isConnected(): Boolean {
        return webSocket != null && isStompConnected
    }
}