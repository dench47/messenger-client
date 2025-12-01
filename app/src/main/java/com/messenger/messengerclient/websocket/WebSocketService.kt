package com.messenger.messengerclient.websocket

import android.os.Handler
import android.util.Log
import com.google.gson.Gson
import com.messenger.messengerclient.config.ApiConfig
import com.messenger.messengerclient.data.model.Message
import okhttp3.*
import java.util.concurrent.TimeUnit

class WebSocketService {

    companion object {
        private const val TAG = "WebSocketService"
    }

    private var webSocket: WebSocket? = null
    private val gson = Gson()
    private var messageListener: ((Message) -> Unit)? = null
    private var username: String? = null

    fun setMessageListener(listener: (Message) -> Unit) {
        this.messageListener = listener
    }

    fun connect(token: String, username: String) {
        this.username = username
        disconnect() // Сначала отключаем старое соединение

        try {
            val client = OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()

            val request = Request.Builder()
                .url("${ApiConfig.WS_BASE_URL}")
                .addHeader("Authorization", "Bearer $token")
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "✅ WebSocket CONNECTED for: $username")

                    // Отправляем STOMP CONNECT фрейм
                    sendStompConnect()

                    // Подписка на персональную очередь (после CONNECTED)
                    Handler().postDelayed({
                        sendSubscribe("/user/$username/queue/messages")
                    }, 100)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "📩 WebSocket RAW: ${text.take(200)}...")

                    // Обрабатываем STOMP фреймы
                    processStompFrame(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "❌ WebSocket failure: ${t.message}", t)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "🔌 WebSocket closed: $reason")
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "💥 WebSocket connection error", e)
        }
    }

    private fun sendStompConnect() {
        val connectFrame = "CONNECT\n" +
                "accept-version:1.1,1.0\n" +
                "heart-beat:10000,10000\n" +
                "\n" +
                "\u0000"

        webSocket?.send(connectFrame)
        Log.d(TAG, "📤 Sent STOMP CONNECT")
    }

    private fun sendSubscribe(destination: String) {
        val subscribeFrame = "SUBSCRIBE\n" +
                "id:sub-${System.currentTimeMillis()}\n" +
                "destination:$destination\n" +
                "\n" +
                "\u0000"

        webSocket?.send(subscribeFrame)
        Log.d(TAG, "📤 Subscribed to: $destination")
    }

    private fun processStompFrame(frame: String) {
        if (frame.startsWith("ERROR")) {
            Log.e(TAG, "❌ STOMP ERROR: $frame")
            return
        }

        if (frame.startsWith("CONNECTED")) {
            Log.d(TAG, "✅ STOMP CONNECTED")
            return
        }

        if (frame.startsWith("MESSAGE")) {
            try {
                // Парсим STOMP MESSAGE фрейм
                val lines = frame.split("\n")

                // Ищем тело сообщения (после пустой строки)
                val emptyLineIndex = lines.indexOfFirst { it.isEmpty() }
                if (emptyLineIndex != -1 && emptyLineIndex < lines.size - 1) {
                    val body = lines[emptyLineIndex + 1]

                    // Парсим JSON тело
                    val message = gson.fromJson(body, Message::class.java)
                    Log.d(TAG, "📨 Parsed message from ${message.senderUsername}: ${message.content}")

                    // Передаем сообщение слушателю
                    messageListener?.invoke(message)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to parse STOMP message", e)
            }
        }
    }

    fun sendMessage(message: Message): Boolean {
        return try {
            val jsonMessage = gson.toJson(message)

            // STOMP SEND фрейм
            val sendFrame = "SEND\n" +
                    "destination:/app/chat\n" +
                    "content-type:application/json\n" +
                    "\n" +
                    "$jsonMessage" +
                    "\u0000"

            webSocket?.send(sendFrame)
            Log.d(TAG, "📤 STOMP SEND to /app/chat")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send via STOMP", e)
            false
        }
    }

    fun disconnect() {
        // Отправляем STOMP DISCONNECT
        val disconnectFrame = "DISCONNECT\n\n\u0000"
        webSocket?.send(disconnectFrame)

        webSocket?.close(1000, "Normal closure")
        webSocket = null
        messageListener = null
        username = null
        Log.d(TAG, "🔌 WebSocket disconnected")
    }

    fun isConnected(): Boolean {
        return webSocket != null
    }
}