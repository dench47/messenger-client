package com.messenger.messengerclient.websocket

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.messenger.messengerclient.config.ApiConfig
import com.messenger.messengerclient.data.model.Message
import okhttp3.*
import java.util.concurrent.TimeUnit

class WebSocketService {

    companion object {
        private const val TAG = "WebSocketService"
        private const val STOMP_HEARTBEAT = "10000,10000"
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

    fun setMessageListener(listener: (Message) -> Unit) {
        this.messageListener = listener
    }

    fun setOnlineStatusListener(listener: (List<String>) -> Unit) {
        this.onlineStatusListener = listener
    }

    fun connect(token: String, username: String) {
        this.username = username
        disconnect()

        try {
            Log.d(TAG, "🔗 Connecting WebSocket for user: $username")

            val client = OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()

            val request = Request.Builder()
                .url(ApiConfig.WS_BASE_URL)
                .addHeader("Authorization", "Bearer $token")
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "✅ WebSocket transport layer CONNECTED for user: $username")
                    isStompConnected = false

                    sendStompConnect(token)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "📩 STOMP raw (${text.length} chars): ${text.replace("\n", "\\n").replace("\u0000", "\\u0000").take(200)}")
                    processStompFrame(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "❌ WebSocket failure for user $username: ${t.message}", t)
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
        val cleanFrame = frame.replace("\u0000", "\\u0000")

        when {
            firstLine == "\n" || frame.trim() == "\n" -> {
                // Это heartbeat от сервера - нужно ответить
                Log.d(TAG, "❤️ Received heartbeat from server, responding...")
                webSocket?.send("\n")  // Отправляем пустую строку как heartbeat ответ
            }

            firstLine.startsWith("ERROR") -> {
                Log.e(TAG, "❌ STOMP ERROR FRAME:\n$cleanFrame")
                isStompConnected = false
            }

            firstLine.startsWith("CONNECTED") -> {
                Log.d(TAG, "✅ STOMP PROTOCOL CONNECTED")
                isStompConnected = true

                // Извлекаем username из фрейма
                var extractedUsername: String? = null
                frame.lines().forEach { line ->
                    if (line.startsWith("user-name:")) {
                        extractedUsername = line.substringAfter("user-name:").trim()
                    }
                }

                val userToSubscribe = extractedUsername ?: username
                Log.d(TAG, "👤 Username extracted: $extractedUsername, will use: $userToSubscribe")

                if (userToSubscribe != null) {
                    // 1. Подписываемся на персональную очередь сообщений
                    messageSubscriptionId = sendSubscribe("/user/queue/messages", "message")

                    // 2. Подписываемся на топик онлайн пользователей
                    onlineStatusSubscriptionId = sendSubscribe("/topic/online.users", "online")

                    Log.d(TAG, "✅ Subscriptions completed for user: $userToSubscribe")
                }
            }

            // Обработка сообщений из персональной очереди
            frame.contains("destination:/user/queue/messages") -> {
                try {
                    Log.d(TAG, "📨 Received message from personal queue")

                    // Извлекаем JSON из фрейма
                    val jsonStart = frame.indexOf('{')
                    val jsonEnd = frame.lastIndexOf('}')

                    if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                        val json = frame.substring(jsonStart, jsonEnd + 1)
                        Log.d(TAG, "📦 Extracted JSON: ${json.take(100)}...")

                        val message = gson.fromJson(json, Message::class.java)
                        Log.d(TAG, "✅ Parsed message: ${message.senderUsername} -> ${message.receiverUsername}")

                        mainHandler.post {
                            messageListener?.invoke(message)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to parse personal message", e)
                }
            }

            // Обработка обновлений онлайн статусов
            frame.contains("destination:/topic/online.users") -> {
                try {
                    Log.d(TAG, "👥 Received online users update")

                    // Извлекаем JSON из фрейма (массив строк)
                    val jsonStart = frame.indexOf('[')
                    val jsonEnd = frame.lastIndexOf(']')

                    if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                        val json = frame.substring(jsonStart, jsonEnd + 1)
                        Log.d(TAG, "📦 Extracted online users JSON: ${json.take(200)}...")

                        val onlineUsers = gson.fromJson(json, Array<String>::class.java).toList()
                        Log.d(TAG, "✅ Parsed online users: ${onlineUsers.size} users")

                        mainHandler.post {
                            onlineStatusListener?.invoke(onlineUsers)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to parse online users", e)
                }
            }

            // Обработка других MESSAGE фреймов (на всякий случай)
            firstLine.startsWith("MESSAGE") -> {
                Log.d(TAG, "ℹ️ Other MESSAGE frame received")
                // Можно добавить дополнительную обработку если нужно
            }

            else -> {
                Log.d(TAG, "ℹ️ Other STOMP frame: $firstLine")
            }
        }
    }

    fun sendMessage(message: Message): Boolean {
        if (!isStompConnected) {
            Log.e(TAG, "❌ Cannot send: STOMP not connected")
            return false
        }

        return try {
            // Удаляем ID при отправке (сервер сам назначит)
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

        // Отправляем DISCONNECT если подключены
        if (isStompConnected) {
            val disconnectFrame = "DISCONNECT\n\n\u0000"
            webSocket?.send(disconnectFrame)
        }

        webSocket?.close(1000, "Normal closure")
        webSocket = null
        messageListener = null
        onlineStatusListener = null
        username = null
        isStompConnected = false
        messageSubscriptionId = null
        onlineStatusSubscriptionId = null
        Log.d(TAG, "🔌 WebSocket fully disconnected")
    }

    fun isConnected(): Boolean {
        return webSocket != null && isStompConnected
    }
}