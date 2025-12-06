package com.messenger.messengerclient.ui

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.messenger.messengerclient.data.model.Message
import com.messenger.messengerclient.databinding.ActivityChatBinding
import com.messenger.messengerclient.network.RetrofitClient
import com.messenger.messengerclient.service.MessageService
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

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Инициализация
        prefsManager = PrefsManager(this)
        RetrofitClient.initialize(this)
        messageService = RetrofitClient.getClient().create(MessageService::class.java)

        // WebSocket инициализация ДО получения данных
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

        // 1. Сначала настраиваем слушатель
        setupWebSocketListener()

        // 2. Потом подключаем WebSocket
        connectWebSocket()

        // 3. Настройка UI
        setupUI()

        // 4. Загрузка истории
        loadMessages()
    }

    @RequiresApi(Build.VERSION_CODES.O)
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

            // Автоматическая прокрутка при изменениях
            (adapter as? MessageAdapter)?.registerAdapterDataObserver(object :
                RecyclerView.AdapterDataObserver() {
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                    super.onItemRangeInserted(positionStart, itemCount)
                    if (messages.isNotEmpty()) {
                        layoutManager?.scrollToPosition(messages.size - 1)
                    }
                }

                override fun onChanged() {
                    super.onChanged()
                    if (messages.isNotEmpty()) {
                        layoutManager?.scrollToPosition(messages.size - 1)
                    }
                }
            })
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
                Log.d("ChatActivity", "📩 WebSocket message received in UI thread")
                Log.d("ChatActivity", "  From: ${message.senderUsername}")
                Log.d("ChatActivity", "  To: ${message.receiverUsername}")
                Log.d("ChatActivity", "  Content: ${message.content}")
                Log.d("ChatActivity", "  Message ID: ${message.id}")

                // Проверяем что сообщение для этого чата
                val isForThisChat =
                    (message.senderUsername == receiverUsername && message.receiverUsername == currentUser) ||
                            (message.senderUsername == currentUser && message.receiverUsername == receiverUsername)

                Log.d("ChatActivity", "  Is for this chat: $isForThisChat")

                if (isForThisChat) {
                    // УЛУЧШЕННАЯ проверка на дубликаты:
                    // 1. Сначала проверяем по ID (самый надежный способ)
                    val existingById = messages.find { it.id == message.id }

                    if (existingById != null) {
                        // Сообщение с таким ID уже есть - обновляем его
                        Log.d("ChatActivity", "  Found existing message by ID, updating...")
                        val index = messages.indexOf(existingById)
                        messages[index] = message
                        messageAdapter.notifyItemChanged(index)
                    } else {
                        // 2. Если нет ID, проверяем по содержанию и отправителю, но только для НЕдавних сообщений
                        val isDuplicate = messages.any { existingMessage ->
                            // Проверяем только если оба сообщения без ID или если сообщение очень свежее (последние 5 секунд)
                            existingMessage.id == null &&
                                    existingMessage.content == message.content &&
                                    existingMessage.senderUsername == message.senderUsername
                        }

                        Log.d("ChatActivity", "  Is duplicate (by content): $isDuplicate")

                        if (!isDuplicate) {
                            Log.d("ChatActivity", "  Adding new message to list")
                            messages.add(message)
                            messageAdapter.submitList(messages.toList())
                            scrollToBottom()
                        } else {
                            Log.d("ChatActivity", "  Duplicate by content - ignoring")
                        }
                    }
                } else {
                    Log.d("ChatActivity", "  Message ignored - not for this chat")
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

    @RequiresApi(Build.VERSION_CODES.O)
    private fun sendMessage() {
        val messageText = binding.etMessage.text.toString().trim()
        if (messageText.isEmpty()) {
            Toast.makeText(this, "Введите сообщение", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d("ChatActivity", "🚀 Sending message: '$messageText' to $receiverUsername")

        // Очищаем поле ввода СРАЗУ
        binding.etMessage.text.clear()

        // Создаем объект сообщения (БЕЗ ID - сервер его назначит)
        val message = Message(
            content = messageText,
            senderUsername = currentUser!!,
            receiverUsername = receiverUsername,
            timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            isRead = false
            // id не указываем - сервер его назначит
        )

        // 1. Отправляем через WebSocket (real-time)
        val wsSuccess = webSocketService.sendMessage(message)

        if (wsSuccess) {
            Log.d("ChatActivity", "✅ Message sent via WebSocket")
            // НЕ добавляем сообщение в список здесь - дождемся ответа от сервера с ID
            // Можно показать индикатор отправки
        } else {
            Log.d("ChatActivity", "⚠️ WebSocket failed, falling back to REST")
            // Если WebSocket не работает, отправляем через REST и добавляем локально
            messages.add(message)
            messageAdapter.submitList(messages.toList())
            scrollToBottom()

            sendViaRestApi(message, messageText)
        }
    }

    private fun sendViaRestApi(message: Message, originalText: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                println("📡 Falling back to REST API")
                val response = messageService.sendMessage(message)

                runOnUiThread {
                    if (response.isSuccessful) {
                        val savedMessage = response.body()
                        println("✅ REST API success: message saved with ID ${savedMessage?.id}")

                        // Обновляем сообщение с ID от сервера
                        val index = messages.indexOfFirst {
                            it.content == originalText && it.senderUsername == currentUser
                        }
                        if (index != -1 && savedMessage != null) {
                            messages[index] = savedMessage
                            messageAdapter.notifyItemChanged(index)
                        }

                        Toast.makeText(
                            this@ChatActivity,
                            "Сообщение отправлено (через REST)",
                            Toast.LENGTH_SHORT
                        ).show()

                        scrollToBottom()
                    } else {
                        println("❌ REST API failed: ${response.code()}")
                        Toast.makeText(this@ChatActivity, "Ошибка отправки", Toast.LENGTH_SHORT)
                            .show()

                        // Удаляем неотправленное сообщение
                        val index = messages.indexOfFirst {
                            it.content == originalText && it.senderUsername == currentUser
                        }
                        if (index != -1) {
                            messages.removeAt(index)
                            messageAdapter.notifyItemRemoved(index)
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    println("💥 REST exception: ${e.message}")
                    Toast.makeText(this@ChatActivity, "Сетевая ошибка", Toast.LENGTH_SHORT).show()
                }
            }
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