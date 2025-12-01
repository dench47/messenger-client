package com.messenger.messengerclient.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.messenger.messengerclient.data.model.Message
import com.messenger.messengerclient.databinding.ActivityChatBinding
import com.messenger.messengerclient.network.RetrofitClient
import com.messenger.messengerclient.network.service.MessageService
import com.messenger.messengerclient.utils.PrefsManager
import com.messenger.messengerclient.websocket.WebSocketManager
import com.messenger.messengerclient.websocket.WebSocketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var prefsManager: PrefsManager
    private lateinit var messageService: MessageService
    private lateinit var messageAdapter: MessageAdapter

    private lateinit var receiverUsername: String
    private lateinit var receiverDisplayName: String
    private var currentUser: String? = null
    private lateinit var webSocketService: WebSocketService

    private val gson = Gson()

    private val messages = mutableListOf<Message>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Инициализация
        prefsManager = PrefsManager(this)
        RetrofitClient.initialize(this)
        messageService = RetrofitClient.getClient().create(MessageService::class.java)
        webSocketService = WebSocketManager.initialize(this)


        // Получение данных из Intent
        currentUser = prefsManager.username
        receiverUsername = intent.getStringExtra("RECEIVER_USERNAME") ?: ""
        receiverDisplayName = intent.getStringExtra("RECEIVER_DISPLAY_NAME") ?: receiverUsername

        // Проверка данных
        if (currentUser.isNullOrEmpty() || receiverUsername.isEmpty()) {
            Toast.makeText(this, "Ошибка: данные пользователя не найдены", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        println("🎯 ChatActivity started:")
        println("  Current user: $currentUser")
        println("  Receiver: $receiverUsername")
        println("  Display name: $receiverDisplayName")

        // Настройка слушателя сообщений
        setupWebSocketListener()

        // Подключение WebSocket
        connectWebSocket()

        // Настройка UI
        setupUI()

        // Загрузка истории сообщений
        loadMessages()
    }

    private fun setupUI() {
        // Заголовок чата
        binding.tvChatWith.text = "Чат с $receiverDisplayName"

        // Инициализация адаптера
        messageAdapter = MessageAdapter(currentUser!!)

        // Настройка RecyclerView
        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(this@ChatActivity).apply {
                stackFromEnd = true // Прокрутка снизу
            }
            adapter = messageAdapter
        }

        // Обработчики кнопок
        binding.btnSend.setOnClickListener {
            sendMessage()
        }

        binding.btnBack.setOnClickListener {
            finish()
        }

        // Отправка по Enter (опционально)
        binding.etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else {
                false
            }
        }
    }

    private fun loadMessages() {
        println("🔄 Loading messages for: $currentUser ↔ $receiverUsername")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = messageService.getConversation(currentUser!!, receiverUsername)

                runOnUiThread {
                    if (response.isSuccessful) {
                        val loadedMessages = response.body() ?: emptyList()
                        println("✅ Loaded ${loadedMessages.size} messages")

                        // Обновляем список
                        messages.clear()
                        messages.addAll(loadedMessages)
                        messageAdapter.submitList(messages.toList())

                        // Прокручиваем к последнему сообщению
                        scrollToBottom()

                        // Обновляем заголовок если нет сообщений
                        if (loadedMessages.isEmpty()) {
                            binding.tvChatWith.text = "Чат с $receiverDisplayName\n(Нет сообщений)"
                        }

                    } else {
                        println("❌ Failed to load messages: ${response.code()} - ${response.message()}")
                        Toast.makeText(
                            this@ChatActivity,
                            "Ошибка загрузки сообщений: ${response.code()}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    println("💥 Error loading messages: ${e.message}")
                    e.printStackTrace()
                    Toast.makeText(
                        this@ChatActivity,
                        "Ошибка сети: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun setupWebSocketListener() {
        webSocketService.setMessageListener { message ->
            runOnUiThread {
                // Проверяем что сообщение для этого чата
                if ((message.senderUsername == receiverUsername && message.receiverUsername == currentUser) ||
                    (message.senderUsername == currentUser && message.receiverUsername == receiverUsername)) {

                    // Проверяем нет ли уже такого сообщения
                    val isDuplicate = messages.any { existingMessage ->
                        existingMessage.id == message.id ||
                                (existingMessage.content == message.content &&
                                        existingMessage.senderUsername == message.senderUsername)
                    }

                    if (!isDuplicate) {
                        println("📩 WebSocket: New message received in real-time")
                        messages.add(message)
                        messageAdapter.submitList(messages.toList())
                        scrollToBottom()
                    }
                }
            }
        }
    }

    private fun connectWebSocket() {
        val token = prefsManager.authToken
        val username = prefsManager.username

        if (!token.isNullOrEmpty() && !username.isNullOrEmpty()) {
            if (!webSocketService.isConnected()) {
                println("🔗 Connecting WebSocket...")
                webSocketService.connect(token, username)
            } else {
                println("✅ WebSocket already connected")
            }
        } else {
            println("⚠️ Cannot connect WebSocket: missing token or username")
        }
    }

    private fun sendMessage() {
        val messageText = binding.etMessage.text.toString().trim()

        if (messageText.isEmpty()) {
            Toast.makeText(this, "Введите сообщение", Toast.LENGTH_SHORT).show()
            return
        }

        println("=".repeat(60))
        println("🚀 START sendMessage()")
        println("  From: $currentUser")
        println("  To: $receiverUsername")
        println("  Content: '$messageText'")

        // Создаем объект сообщения
        val message = Message(
            content = messageText,
            senderUsername = currentUser!!,
            receiverUsername = receiverUsername,
            timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            isRead = false
        )

        println("  Created Message object: ${gson.toJson(message)}")

        // 1. Мгновенное отображение у отправителя
        messages.add(message)
        messageAdapter.submitList(messages.toList())
        scrollToBottom()
        binding.etMessage.text.clear()

        // 2. Отправка через REST API
        CoroutineScope(Dispatchers.IO).launch {
            try {
                println("  📡 Calling REST API: POST /api/messages/send")

                val response = messageService.sendMessage(message)
                println("  📡 Response received:")
                println("    - Code: ${response.code()}")
                println("    - Message: ${response.message()}")
                println("    - Is successful: ${response.isSuccessful}")

                if (response.isSuccessful) {
                    val savedMessage = response.body()
                    println("  ✅ SUCCESS! Message saved in database")
                    println("    - Saved message ID: ${savedMessage?.id}")
                    println("    - Full response: ${gson.toJson(savedMessage)}")

                    runOnUiThread {
                        // Обновляем сообщение с ID от сервера
                        val index = messages.indexOfFirst {
                            it.content == messageText &&
                                    it.senderUsername == currentUser
                        }

                        if (index != -1 && savedMessage != null) {
                            messages[index] = savedMessage
                            messageAdapter.notifyItemChanged(index)
                            println("  🔄 Updated local message with server ID")
                        }

                        Toast.makeText(
                            this@ChatActivity,
                            "Сообщение отправлено",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                } else {
                    println("  ❌ REST API FAILED!")

                    // Пытаемся прочитать тело ошибки
                    try {
                        val errorBody = response.errorBody()?.string()
                        println("    - Error body: $errorBody")
                    } catch (e: Exception) {
                        println("    - Could not read error body: ${e.message}")
                    }

                    runOnUiThread {
                        Toast.makeText(
                            this@ChatActivity,
                            "Ошибка отправки: ${response.code()}",
                            Toast.LENGTH_LONG
                        ).show()

                        // Удаляем сообщение если не сохранилось
                        val index = messages.indexOfFirst {
                            it.content == messageText &&
                                    it.senderUsername == currentUser
                        }
                        if (index != -1) {
                            messages.removeAt(index)
                            messageAdapter.notifyItemRemoved(index)
                            println("  🗑️ Removed local message (not saved on server)")
                        }
                    }
                }

            } catch (e: Exception) {
                println("  💥 EXCEPTION during REST API call:")
                println("    - Type: ${e.javaClass.name}")
                println("    - Message: ${e.message}")
                println("    - Stack trace:")
                e.printStackTrace()

                runOnUiThread {
                    Toast.makeText(
                        this@ChatActivity,
                        "Сетевая ошибка: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()

                    // Удаляем сообщение при ошибке сети
                    val index = messages.indexOfFirst {
                        it.content == messageText &&
                                it.senderUsername == currentUser
                    }
                    if (index != -1) {
                        messages.removeAt(index)
                        messageAdapter.notifyItemRemoved(index)
                        println("  🗑️ Removed local message (network error)")
                    }
                }
            }

            println("🚀 END sendMessage()")
            println("=".repeat(60))
        }
    }


    private fun scrollToBottom() {
        binding.rvMessages.post {
            if (messages.isNotEmpty()) {
                binding.rvMessages.scrollToPosition(messages.size - 1)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}