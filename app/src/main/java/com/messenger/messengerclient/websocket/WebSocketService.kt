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
    private var username: String? = null
    private var isStompConnected = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var subscriptionId: String? = null

    fun setMessageListener(listener: (Message) -> Unit) {
        this.messageListener = listener
    }

    fun connect(token: String, username: String) {
        Log.d(TAG, "🔗 Connecting WebSocket for user: $username")
        this.username = username

        disconnect()

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

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "✅ WebSocket transport layer CONNECTED for user: $username")
                    isStompConnected = false

                    // Отправляем STOMP CONNECT фрейм с JWT
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
                "Authorization:Bearer $token\n" + // ДОБАВЬТЕ ЭТУ СТРОЧКУ
                "\n" +
                "\u0000"

        webSocket?.send(connectFrame)
        Log.d(TAG, "📤 Sent STOMP CONNECT with Authorization header")
    }

    private fun sendSubscribe(destination: String) {
        if (webSocket == null) {
            Log.e(TAG, "❌ Cannot subscribe: WebSocket is null!")
            return
        }

        subscriptionId = "sub-${System.currentTimeMillis()}"
        val subscribeFrame = "SUBSCRIBE\n" +
                "id:${subscriptionId}\n" +
                "destination:$destination\n" +
                "\n" +
                "\u0000"

        Log.d(TAG, "📤 SENDING SUBSCRIBE to: $destination")
        Log.d(TAG, "Frame: ${subscribeFrame.replace("\n", "\\n").replace("\u0000", "\\u0000")}")

        val success = webSocket?.send(subscribeFrame)
        Log.d(TAG, "✅ Subscribe sent (success=$success) to: $destination (id: $subscriptionId)")
    }
    private fun processStompFrame(frame: String) {
        Log.d(TAG, "👤 Current username value: $username")

        val firstLine = frame.lines().firstOrNull() ?: ""
        val cleanFrame = frame.replace("\u0000", "\\u0000")
        Log.d(TAG, "🔄 Processing STOMP frame type: $firstLine")

        when {
            firstLine.startsWith("ERROR") -> {
                Log.e(TAG, "❌ STOMP ERROR FRAME:\n$cleanFrame")
                isStompConnected = false
            }

            firstLine.startsWith("CONNECTED") -> {
                Log.d(TAG, "✅ STOMP PROTOCOL CONNECTED. Server says: $cleanFrame")
                isStompConnected = true

                // 1. Извлекаем username из фрейма сервера
                var extractedUsername: String? = null
                frame.lines().forEach { line ->
                    if (line.startsWith("user-name:")) {
                        extractedUsername = line.substringAfter("user-name:").trim()
                    }
                }

                // 2. Если извлекли - используем его, иначе берем сохраненный
                val userToSubscribe = extractedUsername ?: username
                Log.d(TAG, "👤 Username extracted: $extractedUsername, stored: $username, will use: $userToSubscribe")

                if (userToSubscribe != null) {
                    // КРИТИЧЕСКОЕ: Подписываемся ТОЛЬКО на один вариант
                    // Согласно серверу (MessageController): convertAndSendToUser(username, "/queue/messages", ...)
                    // Значит нужно подписаться на: /user/queue/messages
                    sendSubscribe("/user/queue/messages")
                    Log.d(TAG, "📤 Subscribed to personal queue for user: $userToSubscribe")
                } else {
                    Log.e(TAG, "❌ Cannot subscribe: username is null!")
                }
            }

            firstLine.startsWith("MESSAGE") -> {
                try {
                    Log.d(TAG, "📨 Received STOMP MESSAGE frame")

                    // Упрощенный парсинг: ищем JSON в теле
                    val jsonStart = frame.indexOf('{')
                    val jsonEnd = frame.lastIndexOf('}')

                    if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                        val json = frame.substring(jsonStart, jsonEnd + 1)
                        Log.d(TAG, "📦 Extracted JSON: ${json.take(100)}...")

                        val message = gson.fromJson(json, Message::class.java)
                        Log.d(TAG, "✅ Parsed message: ${message.senderUsername} -> ${message.receiverUsername}: ${message.content.take(30)}...")

                        // Передаем в UI поток
                        mainHandler.post {
                            messageListener?.invoke(message)
                        }
                    } else {
                        Log.e(TAG, "❌ No JSON found in MESSAGE frame")
                        Log.d(TAG, "Full frame: $cleanFrame")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to parse STOMP message", e)
                    Log.d(TAG, "Problematic frame: $cleanFrame")
                }
            }

            else -> {
                Log.d(TAG, "ℹ️ Other STOMP frame: $cleanFrame")
            }
        }
    }
    fun sendMessage(message: Message): Boolean {
        if (!isStompConnected) {
            Log.e(TAG, "❌ Cannot send: STOMP not connected")
            return false
        }

        return try {
            val jsonMessage = gson.toJson(message)

            // КРИТИЧЕСКОЕ: Правильный SEND фрейм
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
        // Отправляем STOMP UNSUBSCRIBE если есть подписка
        subscriptionId?.let { id ->
            val unsubscribeFrame = "UNSUBSCRIBE\nid:$id\n\n\u0000"
            webSocket?.send(unsubscribeFrame)
        }

        // Отправляем STOMP DISCONNECT если подключены
        if (isStompConnected) {
            val disconnectFrame = "DISCONNECT\n\n\u0000"
            webSocket?.send(disconnectFrame)
        }

        webSocket?.close(1000, "Normal closure")
        webSocket = null
        messageListener = null
        username = null
        isStompConnected = false
        subscriptionId = null
        Log.d(TAG, "🔌 WebSocket fully disconnected")
    }

    fun isConnected(): Boolean {
        return webSocket != null && isStompConnected
    }
}