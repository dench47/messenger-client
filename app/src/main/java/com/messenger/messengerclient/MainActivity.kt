package com.messenger.messengerclient

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.messenger.messengerclient.data.local.AppDatabase
import com.messenger.messengerclient.data.local.LocalContact
import com.messenger.messengerclient.data.model.Conversation
import com.messenger.messengerclient.data.model.Message
import com.messenger.messengerclient.data.model.User
import com.messenger.messengerclient.databinding.ActivityMainBinding
import com.messenger.messengerclient.network.RetrofitClient
import com.messenger.messengerclient.service.MessageService
import com.messenger.messengerclient.service.MessengerService
import com.messenger.messengerclient.service.UserService
import com.messenger.messengerclient.ui.ChatActivity
import com.messenger.messengerclient.ui.ConversationAdapter
import com.messenger.messengerclient.ui.LoginActivity
import com.messenger.messengerclient.ui.SearchUsersActivity
import com.messenger.messengerclient.ui.SettingsActivity
import com.messenger.messengerclient.utils.ActivityCounter
import com.messenger.messengerclient.utils.ActivityCounter.activityStarted
import com.messenger.messengerclient.utils.ActivityCounter.activityStopped
import com.messenger.messengerclient.utils.PrefsManager
import com.messenger.messengerclient.utils.toLocal
import com.messenger.messengerclient.utils.toMessage
import com.messenger.messengerclient.websocket.WebSocketManager
import com.messenger.messengerclient.websocket.WebSocketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefsManager: PrefsManager
    private lateinit var userService: UserService
    private lateinit var messageService: MessageService
    private lateinit var conversationAdapter: ConversationAdapter

    private val db by lazy { AppDatabase.getInstance(this) }
    private var isFirstResume = true

    // Глобальный слушатель статусов
    private val globalStatusListener = { updatedMessage: Message ->
        val currentUser = prefsManager.username
        Log.d("MAIN", "🌍 GLOBAL STATUS: ${updatedMessage.id} -> ${updatedMessage.status}")

        if (updatedMessage.senderUsername == currentUser) {
            val otherUser = updatedMessage.receiverUsername
            updateLastMessageStatus(otherUser, updatedMessage)
        }
    }

    // 👇 ГЛОБАЛЬНЫЙ СЛУШАТЕЛЬ СООБЩЕНИЙ
    private val globalMessageListener = { message: Message ->
        Log.d("MAIN", "🔥🔥🔥 MAINACTIVITY LISTENER CALLED! message: ${message.id}")

        val currentUser = prefsManager.username
        Log.d(
            "MAIN",
            "🌍 GLOBAL MESSAGE: ${message.id} from ${message.senderUsername} to ${message.receiverUsername}"
        )

        // Обновляем список чатов
        if (message.receiverUsername == currentUser || message.senderUsername == currentUser) {
            val otherUser = if (message.senderUsername == currentUser)
                message.receiverUsername
            else
                message.senderUsername

            updateLastMessage(otherUser, message)
        }

        // 👇 КРИТИЧЕСКИ ВАЖНО: если мы ПОЛУЧАТЕЛЬ - отправляем DELIVERED
        if (message.receiverUsername == currentUser && message.senderUsername != currentUser) {
            Log.d("MAIN", "📲 Received message via WebSocket outside chat, sending DELIVERED")

            CoroutineScope(Dispatchers.IO).launch {
                message.id?.let { messageId ->
                    // Обновляем в БД
                    db.messageDao().updateMessageStatusAndRead(
                        messageId = messageId,
                        status = "DELIVERED",
                        isRead = false
                    )

                    // Отправляем на сервер через WebSocket
                    Handler(Looper.getMainLooper()).post {
                        val success = WebSocketService.getInstance().sendStatusConfirmation(
                            messageId = messageId,
                            status = "DELIVERED",
                            username = currentUser
                        )
                        Log.d("MAIN", "📤 DELIVERED sent: $success")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        println("🚀 MainActivity.onCreate()")

        Log.d("MAIN_DEBUG", "=== MAIN ACTIVITY CREATED ===")

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        supportActionBar?.title = "МиМу"

        prefsManager = PrefsManager(this)
        println("📱 Current user: ${prefsManager.username}")

        prefsManager.dumpAllPrefs()
        val sharedPrefs = getSharedPreferences("messenger_prefs", MODE_PRIVATE)
        Log.d("MAIN_DEBUG", "SharedPreferences contains:")
        sharedPrefs.all.forEach { (key, value) ->
            Log.d("MAIN_DEBUG", "  $key = $value")
        }

        val loggedIn = prefsManager.isLoggedIn()
        Log.d("MAIN_DEBUG", "isLoggedIn() = $loggedIn")

        if (!loggedIn) {
            Log.e("MAIN_DEBUG", "❌❌❌ AUTH FAILED! Will redirect to LoginActivity")
            redirectToLogin()
            return
        }

        if (!prefsManager.isLoggedIn()) {
            println("❌ Not authenticated, redirecting to login")
            redirectToLogin()
            return
        }

        RetrofitClient.initialize(this)
        userService = RetrofitClient.getClient().create(UserService::class.java)
        messageService = RetrofitClient.getClient().create(MessageService::class.java)

        println("🛠️ [MainActivity] Setting static callback")
        WebSocketService.setStatusUpdateCallback { onlineUsers ->
            println("👥 [MainActivity] STATIC CALLBACK: $onlineUsers")
            runOnUiThread {
            }
        }

        startMessengerService()

        // 👇 ДОБАВЛЯЕМ ГЛОБАЛЬНЫЕ СЛУШАТЕЛИ
        WebSocketService.getInstance().addStatusListener(globalStatusListener)
        // 👇 ИЗМЕНЕНО: используем addMessageListener вместо setMessageListener
        WebSocketService.getInstance().addMessageListener(globalMessageListener)

        // 👇 ДОБАВЛЯЕМ СЛУШАТЕЛЬ ЗАВЕРШЕНИЯ СЕССИИ
        WebSocketService.getInstance().setSessionTerminatedListener { data ->
            Log.w("MAIN", "⚠️ Session terminated by another device! Data: $data")
            runOnUiThread {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Сессия завершена")
                    .setMessage("Вы вошли на другом устройстве. Сессия будет закрыта.")
                    .setCancelable(false)
                    .setPositiveButton("OK") { _, _ ->
                        performLogout()
                    }
                    .show()
            }
        }


        setupUI()
        loadContacts()

        println("✅ MainActivity setup complete")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }

            R.id.action_logout -> {
                performLogout()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun redirectToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    private fun setupUI() {
        conversationAdapter = ConversationAdapter { conversation ->
            openChatWith(conversation.user)
        }

        binding.rvConversations.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = conversationAdapter
        }

        binding.fabAddContact.setOnClickListener {
            startActivity(Intent(this, SearchUsersActivity::class.java))
        }
    }

    private fun openChatWith(user: User) {
        ActivityCounter.startActivityTransition("ChatActivity")
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("RECEIVER_USERNAME", user.username)
            putExtra("RECEIVER_DISPLAY_NAME", user.displayName ?: user.username)
        }
        startActivity(intent)
    }

    private fun loadContacts() {
        val currentUser = prefsManager.username ?: return
        println("🔄 Loading contacts for: $currentUser")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = userService.getContacts(currentUser)

                if (response.isSuccessful) {
                    val contactDtos = response.body()!!
                    val users = contactDtos.map { it.toUser() }

                    val localContacts = users.map { user ->
                        LocalContact(
                            id = user.id ?: 0,
                            username = user.username,
                            displayName = user.displayName,
                            avatarUrl = user.avatarUrl,
                            status = user.status,
                            lastSeenText = user.lastSeenText,
                            ownerUsername = currentUser
                        )
                    }
                    db.contactDao().insertContacts(localContacts)

                    runOnUiThread {
                        updateConversations(users)

                    }
                } else {
                    loadContactsFromDb(currentUser)
                }
            } catch (e: Exception) {
                println("💥 Network error: ${e.message}")
                loadContactsFromDb(currentUser)
            }
        }
    }

    private fun loadContactsFromDb(currentUser: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val localContacts = db.contactDao().getContacts(currentUser)
            val users = localContacts.map { local ->
                User(
                    id = local.id,
                    username = local.username,
                    displayName = local.displayName,
                    avatarUrl = local.avatarUrl,
                    status = local.status,
                    lastSeenText = local.lastSeenText
                )
            }

            runOnUiThread {
                if (users.isNotEmpty()) {
                    updateConversations(users)
                    Toast.makeText(this@MainActivity, "Загружено из кэша", Toast.LENGTH_SHORT)
                        .show()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Нет данных. Подключитесь к интернету",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun updateConversations(users: List<User>) {
        CoroutineScope(Dispatchers.IO).launch {
            val conversations = users.map { user ->
                val lastMessage =
                    db.messageDao().getConversation(prefsManager.username!!, user.username)
                        .lastOrNull()
                Conversation(
                    user = user,
                    lastMessage = lastMessage?.toMessage(),
                    lastMessageTime = lastMessage?.timestamp
                )
            }
            runOnUiThread {
                conversationAdapter.submitList(conversations)
                Log.d("MAIN", "✅ Conversations updated, count: ${conversations.size}")
                // 👇 ПОСЛЕ ОБНОВЛЕНИЯ АДАПТЕРА, СИНХРОНИЗИРУЕМ
                syncLastMessagesWithServer()
            }
        }
    }

    private fun updateLastMessage(username: String, message: Message) {
        Log.d(
            "MAIN",
            "📢 updateLastMessage called for $username: ${message.id} - ${message.content} (${message.status})"
        )

        val currentItems = conversationAdapter.getCurrentItems()
        if (currentItems.isEmpty()) {
            Log.d("MAIN", "   Current items empty, skipping")
            return
        }

        val updatedList = currentItems.toMutableList()
        var updated = false
        var updatedPosition = -1

        for (i in updatedList.indices) {
            val conversation = updatedList[i]
            if (conversation.user.username == username) {
                val updatedConversation = conversation.copy(
                    lastMessage = message,
                    lastMessageTime = message.timestamp
                )
                updatedList[i] = updatedConversation
                updated = true
                updatedPosition = i
                Log.d("MAIN", "   Found conversation at position $i, updating")
                break
            }
        }

        if (updated) {
            val sortedList = updatedList.sortedByDescending { it.lastMessageTime }
            conversationAdapter.submitList(sortedList)
            Log.d("MAIN", "✅ Updated UI for $username at position $updatedPosition")
        } else {
            Log.d("MAIN", "⚠️ Conversation not found for $username")
        }
    }

    private fun updateLastMessageStatus(username: String, updatedMessage: Message) {
        val currentItems = conversationAdapter.getCurrentItems()
        if (currentItems.isEmpty()) return

        val updatedList = currentItems.toMutableList()
        var updated = false

        for (i in updatedList.indices) {
            val conversation = updatedList[i]
            if (conversation.user.username == username) {
                val updatedLastMessage = conversation.lastMessage?.withStatus(updatedMessage.status)
                val updatedConversation = conversation.copy(
                    lastMessage = updatedLastMessage
                )
                updatedList[i] = updatedConversation
                updated = true
                Log.d(
                    "MAIN",
                    "📊 Updated status for chat with $username to ${updatedMessage.status}"
                )
                break
            }
        }

        if (updated) {
            runOnUiThread {
                conversationAdapter.submitList(updatedList)
                Log.d("MAIN", "📊 Conversation list updated with status: ${updatedMessage.status}")
            }
        }
    }

    private fun syncLastMessagesWithServer() {
        val currentUser = prefsManager.username ?: return
        val contacts = conversationAdapter.getCurrentItems().map { it.user.username }

        if (contacts.isEmpty()) {
            Log.d("MAIN", "   No contacts, skipping")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            contacts.forEach { contact ->
                try {
                    Log.d("MAIN", "📡 Requesting last message for $contact")
                    val response = messageService.getLastMessage(currentUser, contact)
                    Log.d("MAIN", "📡 Response code: ${response.code()}")

                    if (response.isSuccessful) {
                        val serverMessage = response.body()
                        Log.d(
                            "MAIN",
                            "📡 Server message: ${serverMessage?.id} - ${serverMessage?.content} (${serverMessage?.status})"
                        )

                        if (serverMessage != null) {
                            // Сохраняем в БД
                            db.messageDao().insertMessage(serverMessage.toLocal())
                            Log.d("MAIN", "💾 Saved to DB: ${serverMessage.id}")

                            runOnUiThread {
                                updateLastMessage(contact, serverMessage)
                            }
                        } else {
                            Log.d("MAIN", "⚠️ Server returned null message for $contact")
                        }
                    } else {
                        Log.e(
                            "MAIN",
                            "❌ Failed to get last message for $contact: ${response.code()}"
                        )
                    }
                } catch (e: Exception) {
                    Log.e("MAIN", "❌ Error syncing message for $contact", e)
                }
            }
            Log.d("MAIN", "✅ syncLastMessagesWithServer COMPLETED")
        }
    }

    private fun performLogout() {
        println("🚪 LOGOUT clicked")

        stopMessengerService()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val userService = RetrofitClient.getClient().create(UserService::class.java)
                val username = prefsManager.username

                if (!username.isNullOrEmpty()) {
                    val logoutRequest = mapOf("username" to username)
                    userService.logout(logoutRequest)
                    println("📡 Logout API called for $username")

                    val removeFcmRequest = mapOf("username" to username)
                    userService.removeFcmToken(removeFcmRequest)
                    println("🗑️ FCM token removed from server")
                }
            } catch (e: Exception) {
                println("⚠️ Logout API error: ${e.message}")
            }
        }

        WebSocketManager.disconnect()
        println("🔌 WebSocket disconnected")

        prefsManager.clear()
        println("🗑️ Local data cleared")

        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
        println("✅ Logout completed")
    }

    private fun startMessengerService() {
        println("🚀 [MainActivity] Starting MessengerService")

        val intent = Intent(this, MessengerService::class.java).apply {
            action = MessengerService.ACTION_START
        }
        startForegroundService(intent)
    }

    private fun stopMessengerService() {
        println("🛑 Stopping MessengerService")
        val intent = Intent(this, MessengerService::class.java).apply {
            action = MessengerService.ACTION_STOP
        }
        stopService(intent)
    }

    override fun onResume() {
        super.onResume()
        activityStarted("MainActivity")
        ActivityCounter.updateCurrentActivity("MainActivity")
        println("🔄 MainActivity.onResume()")
// 👇 Добавляем слушатели только если их нет
        if (!WebSocketService.getInstance().hasMessageListener(globalMessageListener)) {
            WebSocketService.getInstance().addMessageListener(globalMessageListener)
            Log.d("MAIN", "📋 Message listener re-added in onResume")
        }

        if (!WebSocketService.getInstance().hasStatusListener(globalStatusListener)) {
            WebSocketService.getInstance().addStatusListener(globalStatusListener)
            Log.d("MAIN", "📋 Status listener re-added in onResume")
        }
        if (!isFirstResume) {
            syncLastMessagesWithServer()
        }
        isFirstResume = false
    }


    override fun onPause() {
        super.onPause()
        activityStopped()
        println("⏸️ MainActivity.onPause()")
    }

    override fun onDestroy() {
        super.onDestroy()
        println("💀 MainActivity.onDestroy()")

        if (isFinishing) {
            WebSocketService.clearStatusUpdateCallback()
            WebSocketService.setUserEventListener(null)

            // 👇 ОЧИЩАЕМ СЛУШАТЕЛЬ ЗАВЕРШЕНИЯ СЕССИИ
            WebSocketService.getInstance().setSessionTerminatedListener(null)

            WebSocketService.getInstance().removeStatusListener(globalStatusListener)
            // 👇 УДАЛЯЕМ СЛУШАТЕЛЬ СООБЩЕНИЙ
            WebSocketService.getInstance().removeMessageListener(globalMessageListener)

            stopMessengerService()
        }
    }
}