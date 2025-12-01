package com.messenger.messengerclient.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.messenger.messengerclient.databinding.ActivityMainBinding
import com.messenger.messengerclient.network.RetrofitClient
import com.messenger.messengerclient.network.service.UserService
import com.messenger.messengerclient.utils.PrefsManager
import com.messenger.messengerclient.websocket.WebSocketManager
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

        // 2. Простая проверка авторизации
        if (prefsManager.authToken.isNullOrEmpty() || prefsManager.username.isNullOrEmpty()) {
            println("❌ Not authenticated, redirecting to login")
            redirectToLogin()
            return
        }

        // 3. Инициализация Retrofit
        RetrofitClient.initialize(this)
        userService = RetrofitClient.getClient().create(UserService::class.java)

        // Подключаем WebSocket при запуске MainActivity
        WebSocketManager.connectIfNeeded(this)

        // 4. Настройка UI
        setupUI()

        // 5. Загрузка пользователей
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
            override fun onUserClick(user: com.messenger.messengerclient.data.model.User) {
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

    private fun openChatWith(user: com.messenger.messengerclient.data.model.User) {
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

        // 1. Отключаем WebSocket
        WebSocketManager.disconnect()
        println("🔌 WebSocket disconnected")

        // 2. Очищаем данные
        prefsManager.clear()
        println("🗑️ Local data cleared")

        // 3. Переходим на LoginActivity
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)

        // 4. Завершаем Activity
        finish()

        println("✅ Logout completed")
    }

    override fun onDestroy() {
        super.onDestroy()
        println("🔚 MainActivity.onDestroy()")

        // НИЧЕГО не делаем здесь - просто логируем
        // Весь cleanup делается в performLogout()
    }
}