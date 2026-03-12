package com.messenger.messengerclient.ui

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.messenger.messengerclient.R
import com.messenger.messengerclient.config.ApiConfig
import com.messenger.messengerclient.data.local.AppDatabase
import com.messenger.messengerclient.data.model.Message
import com.messenger.messengerclient.databinding.ActivityChatBinding
import com.messenger.messengerclient.network.RetrofitClient
import com.messenger.messengerclient.service.MessageService
import com.messenger.messengerclient.service.UserService
import com.messenger.messengerclient.utils.ActivityCounter
import com.messenger.messengerclient.utils.ActivityCounter.activityStarted
import com.messenger.messengerclient.utils.ActivityCounter.updateCurrentActivity
import com.messenger.messengerclient.utils.PrefsManager
import com.messenger.messengerclient.utils.toLocal
import com.messenger.messengerclient.utils.toMessage
import com.messenger.messengerclient.websocket.WebSocketManager
import com.messenger.messengerclient.websocket.WebSocketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var prefsManager: PrefsManager
    private lateinit var messageService: MessageService
    private lateinit var userService: UserService
    private lateinit var messageAdapter: MessageAdapter

    private lateinit var receiverUsername: String
    private lateinit var receiverDisplayName: String
    private var currentUser: String? = null
    private lateinit var webSocketService: WebSocketService

    private val db by lazy { AppDatabase.getInstance(this) }

    private val messages = mutableListOf<Message>()

    // 👇 Флаг для отслеживания видимости Activity
    private var isResumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ActivityCounter.startActivityTransition("ChatActivity")

        // Инициализация
        prefsManager = PrefsManager(this)
        RetrofitClient.initialize(this)
        messageService = RetrofitClient.getClient().create(MessageService::class.java)
        userService = RetrofitClient.getClient().create(UserService::class.java)

        webSocketService = WebSocketManager.initialize(this)

        currentUser = prefsManager.username
        receiverUsername = intent.getStringExtra("RECEIVER_USERNAME") ?: ""
        receiverDisplayName = intent.getStringExtra("RECEIVER_DISPLAY_NAME") ?: receiverUsername

        if (currentUser.isNullOrEmpty() || receiverUsername.isEmpty()) {
            Toast.makeText(this, "Ошибка: данные пользователя не найдены", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupWebSocketListener()
        connectWebSocket()
        setupUI()
        setupScrollButton()
        setupCallButtons()
        loadMessages()
        setupStatusListener()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == android.R.id.home) {
            finish()
            true
        } else super.onOptionsItemSelected(item)
    }

    private fun setupUI() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        binding.tvName.text = receiverDisplayName
        loadAvatar()

        messageAdapter = MessageAdapter(currentUser!!)

        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(this@ChatActivity).apply {
                stackFromEnd = true
            }

            adapter = messageAdapter

            // Только вертикальные отступы
            addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                    outRect: Rect,
                    view: View,
                    parent: RecyclerView,
                    state: RecyclerView.State
                ) {
                    val baseMargin = dpToPx(4f)
                    outRect.top = baseMargin / 2
                    outRect.bottom = baseMargin / 2
                }
            })

            (adapter as? MessageAdapter)?.registerAdapterDataObserver(object :
                RecyclerView.AdapterDataObserver() {
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                    super.onItemRangeInserted(positionStart, itemCount)
                    if (messages.isNotEmpty()) {
                        scrollToPosition(messages.size - 1)
                    }
                }

                override fun onChanged() {
                    super.onChanged()
                    if (messages.isNotEmpty()) {
                        scrollToPosition(messages.size - 1)
                    }
                }
            })
        }

        binding.btnSend.setOnClickListener { sendMessage() }
        binding.etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }
    }

    // Вспомогательная функция для конвертации dp в пиксели
    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun loadAvatar() {
        prefsManager.username ?: return

        val localFile = File(filesDir, "avatar_${receiverUsername}.jpg")
        if (localFile.exists()) {
            Glide.with(this)
                .load(localFile)
                .circleCrop()
                .into(binding.ivAvatar)
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = userService.getUser(receiverUsername)
                runOnUiThread {
                    if (response.isSuccessful) {
                        val user = response.body()
                        if (!user?.avatarUrl.isNullOrEmpty()) {
                            val fullAvatarUrl = ApiConfig.BASE_URL + user.avatarUrl

                            Glide.with(this@ChatActivity)
                                .load(fullAvatarUrl)
                                .circleCrop()
                                .into(binding.ivAvatar)

                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val url = URL(fullAvatarUrl)
                                    val connection = url.openConnection()
                                    connection.connect()
                                    val inputStream = connection.getInputStream()
                                    val file = File(filesDir, "avatar_${receiverUsername}.jpg")
                                    FileOutputStream(file).use { outputStream ->
                                        inputStream.copyTo(outputStream)
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        } else {
                            binding.ivAvatar.setImageResource(R.drawable.ic_default_avatar)
                        }
                    } else {
                        binding.ivAvatar.setImageResource(R.drawable.ic_default_avatar)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.ivAvatar.setImageResource(R.drawable.ic_default_avatar)
                }
                e.printStackTrace()
            }
        }
    }

    private fun loadInitialStatus() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = userService.getUserStatus(receiverUsername)
                runOnUiThread {
                    if (response.isSuccessful) {
                        val contactDto = response.body()
                        val isOnline = contactDto?.online ?: false
                        binding.tvStatus.text = if (isOnline) "online" else (contactDto?.lastSeenText ?: "")
                        binding.tvStatus.setTextColor(
                            if (isOnline) android.graphics.Color.GREEN else android.graphics.Color.GRAY
                        )
                        Log.d("ChatActivity", "✅ Initial status loaded: ${binding.tvStatus.text} for $receiverUsername")
                    } else {
                        Log.e("ChatActivity", "❌ Failed to load initial status: ${response.code()}")
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatActivity", "❌ Error loading initial status", e)
            }
        }
    }

    private fun setupStatusListener() {
        WebSocketService.setUserEventListener { event ->
            if (event.username == receiverUsername) {
                runOnUiThread {
                    val statusText = if (event.online) "online" else (event.lastSeenText ?: "offline")
                    val statusColor = if (event.online) android.graphics.Color.GREEN else android.graphics.Color.GRAY

                    binding.tvStatus.text = statusText
                    binding.tvStatus.setTextColor(statusColor)

                    Log.d("ChatActivity", "✅ Status updated via WebSocket: $statusText for $receiverUsername")
                }
            }
        }
    }

    private fun setupCallButtons() {
        binding.btnAudioCall.setOnClickListener { startCall(audioOnly = true) }
        binding.btnVideoCall.setOnClickListener { startCall(audioOnly = false) }
    }

    private fun startCall(audioOnly: Boolean) {
        ActivityCounter.startActivityTransition("CallActivity")
        val intent = Intent(this, CallActivity::class.java).apply {
            putExtra(CallActivity.EXTRA_CALL_TYPE, if (audioOnly) "audio" else "video")
            putExtra(CallActivity.EXTRA_TARGET_USER, receiverUsername)
            putExtra(CallActivity.EXTRA_IS_INCOMING, false)
        }
        startActivity(intent)
    }

    private fun loadMessages() {
        CoroutineScope(Dispatchers.IO).launch {
            val localMessages = db.messageDao().getConversation(currentUser!!, receiverUsername)

            runOnUiThread {
                messages.clear()
                messages.addAll(localMessages.map { it.toMessage() })
                messageAdapter.submitList(messages.toList())
                scrollToBottom()
            }

            try {
                val response = messageService.getConversation(currentUser!!, receiverUsername)
                if (response.isSuccessful) {
                    val serverMessages = response.body() ?: emptyList()

                    db.messageDao().insertAllMessages(serverMessages.map { it.toLocal() })

                    runOnUiThread {
                        messages.clear()
                        messages.addAll(serverMessages)
                        messageAdapter.submitList(messages.toList())
                        scrollToBottom()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun setupWebSocketListener() {
        // Слушатель новых сообщений
        webSocketService.setMessageListener { message ->
            CoroutineScope(Dispatchers.IO).launch {
                db.messageDao().insertMessage(message.toLocal())
            }
            runOnUiThread {
                val isForThisChat = (message.senderUsername == receiverUsername && message.receiverUsername == currentUser) ||
                        (message.senderUsername == currentUser && message.receiverUsername == receiverUsername)

                if (isForThisChat) {
                    // Если это сообщение для текущего чата
                    messages.add(message)
                    messageAdapter.submitList(messages.toList())
                    scrollToBottom()

                    // 👇 Если мы ПОЛУЧАТЕЛЬ (нам прислали сообщение) - отправляем DELIVERED
                    if (message.senderUsername == receiverUsername && message.receiverUsername == currentUser) {
                        Log.d("ChatActivity", "📊 Received message, sending DELIVERED confirmation")
                        webSocketService.sendStatusConfirmation(
                            message.id!!,
                            "DELIVERED",
                            currentUser!!
                        )

                        // 👇 Если чат открыт и мы его видим - отправляем READ
                        if (isResumed) {
                            Log.d("ChatActivity", "📊 Chat is open, sending READ confirmation")
                            webSocketService.sendStatusConfirmation(
                                message.id!!,
                                "READ",
                                currentUser!!
                            )

                            // Отправляем на REST API для надежности
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    messageService.markAsRead(message.id!!)
                                } catch (e: Exception) {
                                    Log.e("ChatActivity", "❌ Failed to mark as read via REST", e)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 👇 Слушатель обновлений статусов (для исходящих сообщений)
        webSocketService.setStatusListener { updatedMessage ->
            runOnUiThread {
                // Находим сообщение в списке и обновляем его статус
                val index = messages.indexOfFirst { it.id == updatedMessage.id }
                if (index != -1) {
                    val oldMessage = messages[index]
                    // Создаем копию с обновленным статусом
                    val newMessage = oldMessage.copy(status = updatedMessage.status)
                    messages[index] = newMessage
                    messageAdapter.notifyItemChanged(index)

                    Log.d("ChatActivity", "📊 Message ${updatedMessage.id} status updated to ${updatedMessage.status}")
                }
            }
        }
    }

    private fun connectWebSocket() {
        val token = prefsManager.authToken
        val username = prefsManager.username
        if (!token.isNullOrEmpty() && !username.isNullOrEmpty() && !webSocketService.isConnected()) {
            webSocketService.connect(token, username)
        }
    }

    private fun sendMessage() {
        val messageText = binding.etMessage.text.toString().trim()
        if (messageText.isEmpty()) return

        binding.etMessage.text.clear()

        val message = Message(
            content = messageText,
            senderUsername = currentUser!!,
            receiverUsername = receiverUsername,
            timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            isRead = false,
            status = "SENT"  // 👉 Явно указываем статус SENT
        )

        if (!webSocketService.sendMessage(message)) {
            messages.add(message)
            messageAdapter.submitList(messages.toList())
            scrollToBottom()
            sendViaRestApi(message, messageText)
        }
    }

    private fun sendViaRestApi(message: Message, originalText: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = messageService.sendMessage(message)
                runOnUiThread {
                    if (response.isSuccessful) {
                        val savedMessage = response.body()
                        val index = messages.indexOfFirst {
                            it.content == originalText && it.senderUsername == currentUser
                        }
                        if (index != -1 && savedMessage != null) {
                            // Обновляем ID и статус из ответа сервера
                            messages[index] = savedMessage
                            messageAdapter.notifyItemChanged(index)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
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

    private fun setupScrollButton() {
        binding.rvMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val lastVisibleItem = layoutManager.findLastVisibleItemPosition()

                if (lastVisibleItem < messages.size - 5) {
                    binding.fabScrollToBottom.show()
                } else {
                    binding.fabScrollToBottom.hide()
                }
            }
        })

        binding.fabScrollToBottom.setOnClickListener {
            scrollToBottom()
            binding.fabScrollToBottom.hide()
        }
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        activityStarted("ChatActivity")
        updateCurrentActivity("ChatActivity", receiverUsername)
        setupWebSocketListener()
        setupStatusListener()
        loadInitialStatus()
        loadMessages()

        // 👇 Отправляем READ для всех непрочитанных сообщений в этом чате
        CoroutineScope(Dispatchers.IO).launch {
            // Исправлено: getUnreadMessages принимает только username
            val allUnreadMessages = db.messageDao().getUnreadMessages(currentUser!!)

            // Фильтруем только сообщения от текущего собеседника
            val unreadFromReceiver = allUnreadMessages.filter { it.senderUsername == receiverUsername }

            for (message in unreadFromReceiver) {
                if (message.id != 0L) {
                    runOnUiThread {
                        webSocketService.sendStatusConfirmation(
                            message.id,
                            "READ",
                            currentUser!!
                        )
                    }

                    // Отправляем на REST API
                    try {
                        messageService.markAsRead(message.id)
                    } catch (e: Exception) {
                        Log.e("ChatActivity", "❌ Failed to mark as read via REST", e)
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        isResumed = false  // 👈
        ActivityCounter.activityStopped()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            ActivityCounter.clearLastChatPartner()
        }
    }
}