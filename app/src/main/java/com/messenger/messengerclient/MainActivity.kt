package com.messenger.messengerclient

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.messenger.messengerclient.data.model.User
import com.messenger.messengerclient.databinding.ActivityMainBinding
import com.messenger.messengerclient.network.RetrofitClient
import com.messenger.messengerclient.service.MessengerService
import com.messenger.messengerclient.service.UserService
import com.messenger.messengerclient.ui.ChatActivity
import com.messenger.messengerclient.ui.LoginActivity
import com.messenger.messengerclient.ui.UserAdapter
import com.messenger.messengerclient.utils.PrefsManager
import com.messenger.messengerclient.websocket.WebSocketManager
import com.messenger.messengerclient.websocket.WebSocketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {




    private lateinit var binding: ActivityMainBinding
    private lateinit var prefsManager: PrefsManager
    private lateinit var userService: UserService
    private lateinit var userAdapter: UserAdapter



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        println("🚀 MainActivity.onCreate()")

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. Инициализация PrefsManager
        prefsManager = PrefsManager(this)
        println("📱 Current user: ${prefsManager.username}")

        // 2. Проверка авторизации
        if (prefsManager.authToken.isNullOrEmpty() || prefsManager.username.isNullOrEmpty()) {
            println("❌ Not authenticated, redirecting to login")
            redirectToLogin()
            return
        }


        // 4. Инициализация Retrofit
        RetrofitClient.initialize(this)
        userService = RetrofitClient.getClient().create(UserService::class.java)

        // 1. Устанавливаем статический callback ДО запуска Service
        println("🛠️ [MainActivity] Setting static callback")
        WebSocketService.setStatusUpdateCallback { onlineUsers ->
            println("👥 [MainActivity] STATIC CALLBACK: $onlineUsers")
            runOnUiThread {
                updateOnlineStatuses(onlineUsers)
            }
        }

                // После WebSocketService.setStatusUpdateCallback добавь:
        val wsService = WebSocketService.getInstance()
        wsService.setUserEventListener { event ->
            when (event.type) {
                WebSocketService.UserEventType.DISCONNECTED -> {
                    println("🎯 [MainActivity] UserEventListener FIRED: ${event.username}, type: ${event.type}, lastSeen: ${event.lastSeenText}")

                    runOnUiThread {
                        // Обновляем конкретного пользователя
                        val currentList = userAdapter.currentList.toMutableList()
                        println("🎯 [MainActivity] Current list size: ${currentList.size}")
                        println("🎯 [MainActivity] BEFORE submitList: ${currentList.map { it.username to it.lastSeenText }}")

                        var foundIndex = -1
                        currentList.forEachIndexed { index, user ->
                            if (user.username == event.username) {
                                foundIndex = index
                                println("🎯 [MainActivity] FOUND at index $index! Updating ${user.username}")
                                println("🎯 [MainActivity] Old - online: ${user.online}, lastSeenText: '${user.lastSeenText}'")

                                val updatedUser = user.copy(
                                    online = event.online,
                                    status = "offline",
                                    lastSeenText = event.lastSeenText ?: user.lastSeenText
                                )

                                println("🎯 [MainActivity] New - online: ${updatedUser.online}, lastSeenText: '${updatedUser.lastSeenText}'")

                                currentList[index] = updatedUser
                            }
                        }

                        if (foundIndex != -1) {
                            println("🎯 [MainActivity] Submitting updated list with ${currentList.size} users")
                            userAdapter.submitList(currentList)

                            // Принудительное обновление
                            userAdapter.notifyItemChanged(foundIndex)
                            println("🎯 [MainActivity] AFTER submitList and notifyItemChanged")
                        } else {
                            println("🎯 [MainActivity] User ${event.username} not found in list!")
                        }
                    }
                }
            }
        }


        // 5. Запускаем Service
        startMessengerService()

        // 6. Настройка UI
        setupUI()

        // 7. Загрузка пользователей
        loadUsers()



        println("✅ MainActivity setup complete")
    }

    private fun redirectToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    private fun setupUI() {
        // Приветствие
        binding.tvWelcome.text = "Привет, ${prefsManager.displayName ?: prefsManager.username}!"

        // Адаптер пользователей
        userAdapter = UserAdapter(object : UserAdapter.OnUserClickListener {
            override fun onUserClick(user: User) {
                openChatWith(user)
            }
        })

        // RecyclerView
        binding.rvUsers.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = userAdapter
        }

        // Кнопка выхода
        binding.btnLogout.setOnClickListener {
            performLogout()
        }
    }

    private fun openChatWith(user: User) {
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("RECEIVER_USERNAME", user.username)
            putExtra("RECEIVER_DISPLAY_NAME", user.displayName ?: user.username)
        }
        startActivity(intent)
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

                        userAdapter.submitList(filteredUsers)
                        println("✅ Loaded ${filteredUsers.size} users")

                        if (filteredUsers.isEmpty()) {
                            binding.tvWelcome.text = "Привет!\nПока нет других пользователей"
                        }
                    } else {
                        if (response.code() == 401) {
                            println("❌ Token expired")
                            Toast.makeText(this@MainActivity, "Сессия истекла", Toast.LENGTH_LONG).show()
                            redirectToLogin()
                        } else {
                            Toast.makeText(this@MainActivity, "Ошибка загрузки", Toast.LENGTH_SHORT).show()
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
                    val request = mapOf("username" to username)
                    userService.logout(request)
                    println("📡 Logout API called for $username")
                }
            } catch (e: Exception) {
                println("⚠️ Logout API error (ignoring): ${e.message}")
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

        // 1. Получаем Singleton и устанавливаем context
        val wsService = WebSocketService.getInstance()
        wsService.setContext(this)  // ← ВАЖНО!

        // 2. Запускаем Service
        val intent = Intent(this, MessengerService::class.java).apply {
            action = MessengerService.ACTION_START
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
    private fun updateOnlineStatuses(onlineUsers: List<String>) {
        println("👥 [MainActivity] updateOnlineStatuses called with: $onlineUsers")

        val currentList = userAdapter.currentList.toMutableList()
        println("   📊 Current list has ${currentList.size} users")

        currentList.forEachIndexed { index, user ->
            val isOnline = onlineUsers.contains(user.username)
            val updatedUser = user.copy(
                online = isOnline,
                status = if (isOnline) "online" else "offline" // ← ВАЖНО: обновляем status тоже!
            )

            if (user != updatedUser) {
                currentList[index] = updatedUser
                println("   👤 ${user.username}: ${user.status} -> ${updatedUser.status}")
            }
        }

        println("   📤 Submitting new list to adapter")
        userAdapter.submitList(currentList)
        println("   ✅ Adapter notified")
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
        println("🔄 MainActivity.onResume() - app in foreground")

        // ВОССТАНАВЛИВАЕМ ВСЕ СЛУШАТЕЛИ
        val wsService = WebSocketService.getInstance()

        println("🔍 [MainActivity] onResume - WebSocketService identity: ${System.identityHashCode(wsService)}")

        // 1. Статический callback для онлайн статусов
        WebSocketService.setStatusUpdateCallback { onlineUsers ->
            println("👥 [MainActivity] STATIC CALLBACK (resumed): $onlineUsers")
            runOnUiThread {
                updateOnlineStatuses(onlineUsers)
            }
        }

        // 2. Слушатель user events
        wsService.setUserEventListener { event ->
            println("🎯 [MainActivity] UserEventListener (resumed) FIRED: ${event.username}, type: ${event.type}")

            when (event.type) {
                WebSocketService.UserEventType.DISCONNECTED -> {
                    println("🎯 [MainActivity] Processing DISCONNECTED for: ${event.username}")

                    runOnUiThread {
                        val currentList = userAdapter.currentList.toMutableList()
                        println("🎯 [MainActivity] Current list size: ${currentList.size}")

                        var updated = false
                        currentList.forEachIndexed { index, user ->
                            if (user.username == event.username) {
                                println("🎯 [MainActivity] FOUND! Updating ${user.username} with lastSeen: ${event.lastSeenText}")
                                val updatedUser = user.copy(
                                    online = event.online,
                                    status = "offline",
                                    lastSeenText = event.lastSeenText ?: user.lastSeenText
                                )
                                currentList[index] = updatedUser
                                userAdapter.submitList(currentList)
                                userAdapter.notifyItemChanged(index)
                                updated = true
                                println("🎯 [MainActivity] User updated in adapter")
                            }
                        }

                        if (!updated) {
                            println("🎯 [MainActivity] User ${event.username} not found in list")
                        }
                    }
                }
            }
        }

        sendToService(MessengerService.ACTION_APP_FOREGROUND)
    }
    private fun sendToService(action: String) {
        println("   📤 Sending to Service: $action")
        val intent = Intent(this, MessengerService::class.java).apply {
            this.action = action
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            println("   ✅ Intent sent")
        } catch (e: Exception) {
            println("   ❌ Failed to send intent: ${e.message}")
        }
    }



    override fun onPause() {
        super.onPause()
        // onPause вызывается когда activity теряет фокус (Home, другая app поверх)
        println("⏸️ MainActivity.onPause() - app may be going to background")

        // Используем onUserLeaveHint для точного определения Home кнопки
    }


    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // ТОЧНО: пользователь нажал Home или Recent Apps
        println("🏠 MainActivity.onUserLeaveHint() - Home button pressed")

        sendToService(MessengerService.ACTION_APP_BACKGROUND)
    }

    override fun onDestroy() {
        super.onDestroy()
        println("💀 MainActivity.onDestroy()")

        // Очищаем callback
        WebSocketService.clearStatusUpdateCallback()

        // Очищаем user event listener
        WebSocketService.getInstance().setUserEventListener(null)

        if (isFinishing) {
            stopMessengerService()
        }
    }}