package com.messenger.messengerclient

import android.content.Intent
import android.os.Bundle
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
    private lateinit var conversationAdapter: ConversationAdapter

    private val db by lazy { AppDatabase.getInstance(this) }
    private var isFirstResume = true


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        println("🚀 MainActivity.onCreate()")

        Log.d("MAIN_DEBUG", "=== MAIN ACTIVITY CREATED ===")

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Устанавливаем Toolbar как ActionBar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        supportActionBar?.title = "МиМу"

        // 1. Инициализация PrefsManager
        prefsManager = PrefsManager(this)
        println("📱 Current user: ${prefsManager.username}")

        prefsManager.dumpAllPrefs()
        Log.d("MAIN_DEBUG", "Username from prefs: ${prefsManager.username}")

        // ПРЯМАЯ ПРОВЕРКА SharedPreferences
        val sharedPrefs = getSharedPreferences("messenger_prefs", MODE_PRIVATE)
        Log.d("MAIN_DEBUG", "SharedPreferences contains:")
        sharedPrefs.all.forEach { (key, value) ->
            Log.d("MAIN_DEBUG", "  $key = $value")
        }

        // Вызываем isLoggedIn и смотрим что возвращает
        val loggedIn = prefsManager.isLoggedIn()
        Log.d("MAIN_DEBUG", "isLoggedIn() = $loggedIn")

        if (!loggedIn) {
            Log.e("MAIN_DEBUG", "❌❌❌ AUTH FAILED! Will redirect to LoginActivity")
            redirectToLogin()
            return
        }

        // 2. Проверка авторизации
        if (!prefsManager.isLoggedIn()) {
            println("❌ Not authenticated, redirecting to login")
            redirectToLogin()
            return
        }

        // 3. Инициализация Retrofit
        RetrofitClient.initialize(this)
        userService = RetrofitClient.getClient().create(UserService::class.java)

        // 4. Устанавливаем статический callback ДО запуска Service
        println("🛠️ [MainActivity] Setting static callback")
        WebSocketService.setStatusUpdateCallback { onlineUsers ->
            println("👥 [MainActivity] STATIC CALLBACK: $onlineUsers")
            runOnUiThread {
            }
        }


        // 6. Запускаем Service - ЭТО ВСЕ, ЧТО ДЕЛАЕМ С WebSocket!
        startMessengerService()

        // 7. Настройка UI
        setupUI()

        // 8. Загрузка контактов
        loadContacts()
        setupMessageListener()

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
                // Пытаемся загрузить с сервера
                val response = userService.getContacts(currentUser)

                if (response.isSuccessful) {
                    val contactDtos = response.body()!!
                    val users = contactDtos.map { it.toUser() }

                    // Сохраняем в БД
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
                    // Если сервер недоступен — грузим из БД
                    loadContactsFromDb(currentUser)
                }
            } catch (e: Exception) {
                println("💥 Network error: ${e.message}")
                // Грузим из БД
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
            }
        }
    }


    private fun loadUsers() {
        println("🔄 Loading users...")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = userService.getUsers()
                println("📡 Users response: ${response.code()}")

                runOnUiThread {
                    if (response.isSuccessful) {
                        val users = response.body()!!
                        val currentUser = prefsManager.username
                        val filteredUsers = users.filter { it.username != currentUser }

                        // Преобразуем в Conversation
                        val conversations = filteredUsers.map { user ->
                            Conversation(
                                user = user,
                                lastMessage = null,
                                lastMessageTime = null
                            )
                        }

                        conversationAdapter.submitList(conversations)
                        println("✅ Loaded ${conversations.size} conversations")
                    } else {
                        if (response.code() == 401) {
                            println("❌ Token expired")
                            Toast.makeText(this@MainActivity, "Сессия истекла", Toast.LENGTH_LONG)
                                .show()
                            redirectToLogin()
                        } else {
                            Toast.makeText(this@MainActivity, "Ошибка загрузки", Toast.LENGTH_SHORT)
                                .show()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    println("💥 Load users error: ${e.message}")
                    Toast.makeText(this@MainActivity, "Ошибка сети", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }


    private fun setupMessageListener() {
        WebSocketService.getInstance().setMessageListener { message ->
            val currentUser = prefsManager.username
            if (message.receiverUsername == currentUser || message.senderUsername == currentUser) {
                val otherUser = if (message.senderUsername == currentUser)
                    message.receiverUsername
                else
                    message.senderUsername

                updateLastMessage(otherUser, message)
            }
        }
    }

    private fun updateLastMessage(username: String, message: Message) {
        // Получаем текущий список из адаптера
        val currentItems = conversationAdapter.getCurrentItems()
        if (currentItems.isEmpty()) return

        val updatedList = currentItems.toMutableList()
        var updated = false

        for (i in updatedList.indices) {
            val conversation = updatedList[i]
            if (conversation.user.username == username) {
                val updatedConversation = conversation.copy(
                    lastMessage = message,
                    lastMessageTime = message.timestamp
                )
                updatedList[i] = updatedConversation
                updated = true
                break
            }
        }

        if (updated) {
            val sortedList = updatedList.sortedByDescending { it.lastMessageTime }
            conversationAdapter.submitList(sortedList)
        }
    }

    private fun performLogout() {
        println("🚪 LOGOUT clicked")

        // 0. Останавливаем Foreground Service
        stopMessengerService()

        // 1. Отправляем запрос на сервер о logout
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val userService = RetrofitClient.getClient().create(UserService::class.java)
                val username = prefsManager.username

                if (!username.isNullOrEmpty()) {
                    // 1.1. Logout API
                    val logoutRequest = mapOf("username" to username)
                    userService.logout(logoutRequest)
                    println("📡 Logout API called for $username")

                    // 1.2. УДАЛЯЕМ FCM токен с сервера
                    val removeFcmRequest = mapOf("username" to username)
                    userService.removeFcmToken(removeFcmRequest)
                    println("🗑️ FCM token removed from server")
                }
            } catch (e: Exception) {
                println("⚠️ Logout API error: ${e.message}")
            }
        }

        // 2. Отключаем WebSocket
        WebSocketManager.disconnect()
        println("🔌 WebSocket disconnected")

        // 3. Очищаем данные
        prefsManager.clear()
        println("🗑️ Local data cleared")

        // 4. Переходим на LoginActivity
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)

        // 5. Завершаем Activity
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

        // ВОССТАНАВЛИВАЕМ ВСЕ СЛУШАТЕЛИ
        // 1. Статический callback для онлайн статусов
        WebSocketService.setStatusUpdateCallback { onlineUsers ->
            println("👥 [MainActivity] STATIC CALLBACK (resumed): $onlineUsers")
            runOnUiThread {
            }
        }
        if (!isFirstResume) {
            loadContacts()  // обновляем только при возвращении, не при первом запуске
            setupMessageListener()
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

        // Очищаем ТОЛЬКО если Activity завершается (не при повороте)
        if (isFinishing) {
            // Очищаем callback
            WebSocketService.clearStatusUpdateCallback()

            // Очищаем user event listener (статический метод!)
            WebSocketService.setUserEventListener(null)

            stopMessengerService()
        }
    }
}