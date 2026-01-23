package com.messenger.messengerclient

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import com.messenger.messengerclient.utils.ActivityCounter
import com.messenger.messengerclient.utils.ActivityCounter.activityStarted
import com.messenger.messengerclient.utils.ActivityCounter.activityStopped
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

        Log.d("MAIN_DEBUG", "=== MAIN ACTIVITY CREATED ===")

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. Инициализация PrefsManager
        prefsManager = PrefsManager(this)
        println("📱 Current user: ${prefsManager.username}")

        // 2. Проверка авторизации
        if (!prefsManager.isLoggedIn()) {
            println("❌ Not authenticated, redirecting to login")
            redirectToLogin()
            return
        }

        // 3. Инициализация Retrofit
        RetrofitClient.initialize(this)
        userService = RetrofitClient.getClient().create(UserService::class.java)

        // 4. Запускаем Service (ОДИН РАЗ при создании)
        startMessengerService()

        // 5. Устанавливаем статический callback для онлайн статусов
        println("🛠️ [MainActivity] Setting static callback")
        WebSocketService.setStatusUpdateCallback { onlineUsers ->
            println("👥 [MainActivity] STATIC CALLBACK: $onlineUsers")
            runOnUiThread {
                updateOnlineStatuses(onlineUsers)
            }
        }

        // 6. Устанавливаем user event listener
        setupUserEventListener()

        // 7. Настройка UI
        setupUI()

        // 8. Загрузка пользователей
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

        // Кнопка выхода (позже уберём)
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

    private fun setupUserEventListener() {
        // Устанавливаем user event listener ТОЛЬКО через статический метод
        WebSocketService.setUserEventListener { event ->
            runOnUiThread {
                Log.d(
                    "MainActivity",
                    "🎯 UserEvent: type=${event.type}, username=${event.username}, online=${event.online}, lastSeenText='${event.lastSeenText}'"
                )

                val currentList = userAdapter.currentList.toMutableList()
                var updated = false

                currentList.forEachIndexed { index, user ->
                    if (user.username == event.username) {
                        val updatedUser = when (event.type) {
                            WebSocketService.UserEventType.CONNECTED -> {
                                user.copy(online = true, status = "online", lastSeenText = "online")
                            }

                            WebSocketService.UserEventType.INACTIVE -> {
                                user.copy(
                                    online = false,
                                    status = "inactive",
                                    lastSeenText = event.lastSeenText ?: "был недавно"
                                )
                            }

                            WebSocketService.UserEventType.DISCONNECTED -> {
                                user.copy(
                                    online = false,
                                    status = "offline",
                                    lastSeenText = event.lastSeenText
                                )
                            }
                        }

                        Log.d(
                            "MainActivity",
                            "🎯 Updating ${user.username}: online ${user.online}->${updatedUser.online}, status '${user.status}'->'${updatedUser.status}'"
                        )
                        currentList[index] = updatedUser
                        updated = true
                    }
                }

                if (updated) {
                    userAdapter.submitList(currentList)
                    Log.d("MainActivity", "🎯 List submitted")
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

        val wsService = WebSocketService.getInstance()
        wsService.setContext(this)

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
        val currentList = userAdapter.currentList.toMutableList()
        var changed = false

        currentList.forEachIndexed { index, user ->
            // Только обновляем если статус не специальный (inactive/offline)
            if (user.status !in listOf("inactive", "offline")) {
                val isOnline = onlineUsers.contains(user.username)
                val updatedUser = user.copy(
                    online = isOnline,
                    status = if (isOnline) "online" else "offline",
                    lastSeenText = if (isOnline) "online" else user.lastSeenText
                )

                if (user != updatedUser) {
                    currentList[index] = updatedUser
                    changed = true
                }
            }
        }

        if (changed) {
            userAdapter.submitList(currentList)
        }
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
        activityStarted()
        ActivityCounter.updateCurrentActivity("MainActivity")
        println("🔄 MainActivity.onResume()")

        // ВОССТАНАВЛИВАЕМ ВСЕ СЛУШАТЕЛИ ТОЛЬКО для MainActivity
        val wsService = WebSocketService.getInstance()

        println(
            "🔍 [MainActivity] onResume - WebSocketService identity: ${
                System.identityHashCode(
                    wsService
                )
            }"
        )

        // НЕ отправляем ACTION_APP_FOREGROUND! Сервис уже работает, WebSocket уже онлайн
        // sendToService(MessengerService.ACTION_APP_FOREGROUND) ← УБРАТЬ!

        // Просто обновляем статусы если нужно
        if (userAdapter.itemCount > 0) {
            // Можно запросить текущий список онлайн пользователей у WebSocket
            // или дождаться автоматического обновления
        }
    }

    override fun onPause() {
        super.onPause()
        activityStopped()
        println("⏸️ MainActivity.onPause()")

        // НЕ отправляем ACTION_APP_BACKGROUND! Сервис продолжает работать в фоне
        // sendToService(MessengerService.ACTION_APP_BACKGROUND) ← УБРАТЬ!
    }

    override fun onDestroy() {
        super.onDestroy()
        println("💀 MainActivity.onDestroy()")

        // Очищаем слушатель ActivityCounter
        ActivityCounter.removeListener { isForeground ->
            Log.d("MainActivity", "App foreground state changed: $isForeground")
        }

        // Очищаем ТОЛЬКО если Activity завершается (не при повороте)
        if (isFinishing) {
            // Очищаем callback
            WebSocketService.clearStatusUpdateCallback()

            // Очищаем user event listener (статический метод!)
            WebSocketService.setUserEventListener(null)

            // НЕ останавливаем сервис! Он должен работать в фоне
            // stopMessengerService() ← УБРАТЬ!
        }
    }
}