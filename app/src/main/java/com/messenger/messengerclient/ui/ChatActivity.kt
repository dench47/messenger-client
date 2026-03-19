package com.messenger.messengerclient.ui

import android.app.ActivityManager
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.messenger.messengerclient.MainActivity
import com.messenger.messengerclient.R
import com.messenger.messengerclient.config.ApiConfig
import com.messenger.messengerclient.data.local.AppDatabase
import com.messenger.messengerclient.data.model.Message
import com.messenger.messengerclient.databinding.ActivityChatBinding
import com.messenger.messengerclient.network.RetrofitClient
import com.messenger.messengerclient.service.MessageService
import com.messenger.messengerclient.service.MessengerService
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
import kotlinx.coroutines.delay
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

    private var isResumed = false
    private var baseMarginPx = 0

    // Для batch-подтверждений READ (отправка на сервер)
    private val readConfirmationHandler = Handler(Looper.getMainLooper())
    private val pendingReadMessages = mutableSetOf<Long>()
    private val readConfirmationRunnable = Runnable {
        if (pendingReadMessages.isNotEmpty()) {
            val messageIds = pendingReadMessages.toList()
            pendingReadMessages.clear()

            Log.d(
                "ChatActivity",
                "📊 Sending batch READ confirmation for ${messageIds.size} messages"
            )

            CoroutineScope(Dispatchers.IO).launch {
                webSocketService.sendBatchStatusConfirmation(
                    messageIds = messageIds,
                    status = "READ",
                    username = currentUser!!
                )

                messageIds.forEach { messageId ->
                    db.messageDao().updateMessageStatusAndRead(
                        messageId = messageId,
                        status = "READ",
                        isRead = true
                    )
                }
            }
        }
    }

    // Для batch-обновления UI при получении статусов с сервера
    private val pendingStatusUpdates = mutableListOf<Message>()
    private val statusUpdateHandler = Handler(Looper.getMainLooper())
    private val statusUpdateRunnable = Runnable {
        if (pendingStatusUpdates.isNotEmpty()) {
            val updates = pendingStatusUpdates.toList()
            pendingStatusUpdates.clear()

            Log.d("ChatActivity", "📊 Applying batch of ${updates.size} status updates to UI")

            updates.forEach { updatedMessage ->
                val indexInMessages = messages.indexOfFirst { it.id == updatedMessage.id }
                if (indexInMessages != -1) {
                    messages[indexInMessages] =
                        messages[indexInMessages].withStatus(updatedMessage.status)
                }
            }

            val currentList = messageAdapter.currentList.toMutableList()
            var needsUpdate = false

            updates.forEach { updatedMessage ->
                val indexInAdapter = currentList.indexOfFirst { it.id == updatedMessage.id }
                if (indexInAdapter != -1) {
                    currentList[indexInAdapter] =
                        currentList[indexInAdapter].withStatus(updatedMessage.status)
                    needsUpdate = true
                }
            }

            if (needsUpdate) {
                messageAdapter.submitList(currentList)
                Log.d("ChatActivity", "📊 UI updated for ${updates.size} messages")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ActivityCounter.startActivityTransition("ChatActivity")
        baseMarginPx = (4f * resources.displayMetrics.density).toInt()
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
            // Проверяем, запущен ли сервис
            if (!isMessengerServiceRunning()) {
                Log.d("ChatActivity", "🔄 Service not running, back = new app launch")

                // Создаем интент как при новом запуске
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
                finish()
            } else {
                // Сервис запущен - просто закрываем чат
                finish()
            }
            true
        } else super.onOptionsItemSelected(item)
    }

    // Вспомогательный метод для проверки сервиса
    private fun isMessengerServiceRunning(): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (MessengerService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
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

            addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                    outRect: Rect,
                    view: View,
                    parent: RecyclerView,
                    state: RecyclerView.State
                ) {
                    outRect.top = baseMarginPx / 2
                    outRect.bottom = baseMarginPx / 2
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
                        binding.tvStatus.text =
                            if (isOnline) "online" else (contactDto?.lastSeenText ?: "")
                        binding.tvStatus.setTextColor(
                            if (isOnline) android.graphics.Color.GREEN else android.graphics.Color.GRAY
                        )
                        Log.d(
                            "ChatActivity",
                            "✅ Initial status loaded: ${binding.tvStatus.text} for $receiverUsername"
                        )
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
                    val statusText =
                        if (event.online) "online" else (event.lastSeenText ?: "offline")
                    val statusColor =
                        if (event.online) android.graphics.Color.GREEN else android.graphics.Color.GRAY

                    binding.tvStatus.text = statusText
                    binding.tvStatus.setTextColor(statusColor)

                    Log.d(
                        "ChatActivity",
                        "✅ Status updated via WebSocket: $statusText for $receiverUsername"
                    )
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
            Log.d("ChatActivity", "📚 Local messages count: ${localMessages.size}")

            runOnUiThread {
                messages.clear()
                messages.addAll(localMessages.map { it.toMessage() })
                messageAdapter.submitList(messages.toList())
                scrollToBottom()
            }

            try {
                Log.d("ChatActivity", "🌐 Fetching conversation from server...")
                val response = messageService.getConversation(currentUser!!, receiverUsername)
                Log.d("ChatActivity", "🌐 Response code: ${response.code()}")

                if (response.isSuccessful) {
                    val serverMessages = response.body() ?: emptyList()
                    Log.d("ChatActivity", "🌐 Server messages count: ${serverMessages.size}")

                    if (serverMessages.isNotEmpty()) {
                        db.messageDao().insertAllMessages(serverMessages.map { it.toLocal() })
                        Log.d("ChatActivity", "💾 Saved ${serverMessages.size} messages to local DB")

                        runOnUiThread {
                            messages.clear()
                            messages.addAll(serverMessages)
                            messageAdapter.submitList(messages.toList())
                            scrollToBottom()
                        }
                    } else {
                        Log.d("ChatActivity", "⚠️ Server returned empty list")
                    }
                } else {
                    Log.e(
                        "ChatActivity",
                        "❌ Server error: ${response.code()} - ${response.errorBody()?.string()}"
                    )
                }
            } catch (e: Exception) {
                Log.e("ChatActivity", "❌ Network error: ${e.message}")
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
                val isForThisChat =
                    (message.senderUsername == receiverUsername && message.receiverUsername == currentUser) ||
                            (message.senderUsername == currentUser && message.receiverUsername == receiverUsername)

                if (isForThisChat) {
                    val existingIndex = messages.indexOfFirst {
                        it.id != null && it.id == message.id ||
                                (it.id == null && it.content == message.content && it.timestamp == message.timestamp)
                    }

                    if (existingIndex == -1) {
                        Log.d(
                            "ChatActivity",
                            "📨 Adding new message to list: ${message.id} - ${message.content}"
                        )
                        messages.add(message)
                        messageAdapter.submitList(messages.toList())
                        scrollToBottom()
                    } else if (message.id != null && messages[existingIndex].id == null) {
                        Log.d(
                            "ChatActivity",
                            "🔄 Updating temp message ID from null to ${message.id}"
                        )
                        val updatedMessage = messages[existingIndex].copy(id = message.id)
                        messages[existingIndex] = updatedMessage
                        messageAdapter.notifyItemChanged(existingIndex)
                    } else {
                        Log.d("ChatActivity", "⏭️ Message already exists, skipping: ${message.id}")
                    }

                    // 👇 ЕСЛИ МЫ ПОЛУЧАТЕЛЬ - СРАЗУ ОТПРАВЛЯЕМ DELIVERED!
                    if (message.senderUsername != currentUser) {
                        Log.d("ChatActivity", "📲 Received message, sending DELIVERED automatically")

                        CoroutineScope(Dispatchers.IO).launch {
                            message.id?.let { messageId ->
                                webSocketService.sendStatusConfirmation(
                                    messageId = messageId,
                                    status = "DELIVERED",
                                    username = currentUser!!
                                )

                                db.messageDao().updateMessageStatusAndRead(
                                    messageId = messageId,
                                    status = "DELIVERED",
                                    isRead = false
                                )
                            }
                        }
                    }
                }
            }
        }

        // Слушатель обновлений статусов (для исходящих сообщений)
        webSocketService.setStatusListener { updatedMessage ->
            runOnUiThread {
                Log.d(
                    "ChatActivity",
                    "📊 STATUS RECEIVED: ${updatedMessage.id} -> ${updatedMessage.status}, isRead=${updatedMessage.isRead}"
                )

                pendingStatusUpdates.add(updatedMessage)

                statusUpdateHandler.removeCallbacks(statusUpdateRunnable)
                statusUpdateHandler.postDelayed(statusUpdateRunnable, 100)

                CoroutineScope(Dispatchers.IO).launch {
                    db.messageDao().updateMessageStatusAndRead(
                        messageId = updatedMessage.id!!,
                        status = updatedMessage.status,
                        isRead = updatedMessage.isRead
                    )
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
            status = "SENT"
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

    private fun sendUserActivityToServer(activity: String, chatPartner: String?) {
        val activityData = mapOf(
            "type" to "USER_ACTIVITY",
            "username" to currentUser,
            "activity" to activity,
            "chatPartner" to chatPartner
        )
        webSocketService.sendUserActivity(activityData)
    }

    private fun markMessagesAsRead() {
        CoroutineScope(Dispatchers.IO).launch {
            delay(500)

            val unreadMessageIds = db.messageDao().getUnreadMessageIdsFromSender(
                receiver = currentUser!!,
                sender = receiverUsername
            )

            if (unreadMessageIds.isEmpty()) {
                Log.d("ChatActivity", "📊 No messages to mark as READ")
                return@launch
            }

            Log.d("ChatActivity", "📊 Found ${unreadMessageIds.size} messages to mark as READ")

            if (unreadMessageIds.isNotEmpty()) {
                if (webSocketService.isConnected()) {
                    val success = webSocketService.sendBatchStatusConfirmation(
                        messageIds = unreadMessageIds,
                        status = "READ",
                        username = currentUser!!
                    )

                    Log.d("ChatActivity", "📊 Batch send via WebSocket result: $success")

                    unreadMessageIds.forEach { messageId ->
                        db.messageDao().updateMessageStatusAndRead(
                            messageId = messageId,
                            status = "READ",
                            isRead = true
                        )
                    }
                } else {
                    Log.e("ChatActivity", "❌ WebSocket not connected, can't mark as read")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        activityStarted("ChatActivity")
        updateCurrentActivity("ChatActivity", receiverUsername)
        sendUserActivityToServer("ChatActivity", receiverUsername)

        setupWebSocketListener()
        setupStatusListener()
        loadInitialStatus()
        loadMessages()
        markMessagesAsRead()
    }

    override fun onPause() {
        super.onPause()
        isResumed = false
        sendUserActivityToServer("Background", null)

        readConfirmationHandler.removeCallbacks(readConfirmationRunnable)
        pendingReadMessages.clear()

        statusUpdateHandler.removeCallbacks(statusUpdateRunnable)
        pendingStatusUpdates.clear()

        ActivityCounter.activityStopped()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            ActivityCounter.clearLastChatPartner()
        }
    }
}