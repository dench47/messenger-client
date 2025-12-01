package com.messenger.messengerclient.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.messenger.messengerclient.data.model.AuthRequest
import com.messenger.messengerclient.databinding.ActivityLoginBinding
import com.messenger.messengerclient.network.RetrofitClient
import com.messenger.messengerclient.network.service.AuthService
import com.messenger.messengerclient.network.service.UserService
import com.messenger.messengerclient.utils.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var prefsManager: PrefsManager
    private lateinit var authService: AuthService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsManager = PrefsManager(this)

        // Инициализация RetrofitClient
        RetrofitClient.initialize(this)
        authService = RetrofitClient.getClient().create(AuthService::class.java)

        // ========== Проверка токена при запуске ==========
        if (prefsManager.isLoggedIn()) {
            // Токен есть и не истек, проверяем его валидность на сервере
            validateTokenAndAutoLogin()
        } else {
            // Показываем экран логина
            setupUI()
        }
    }

    private fun setupUI() {
        // Настройка обработчиков
        binding.btnLogin.setOnClickListener {
            val username = binding.etUsername.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Введите логин и пароль", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            login(username, password)
        }

        // Очищаем поля если нужно
        binding.etUsername.setText("")
        binding.etPassword.setText("")

        // Устанавливаем фокус
        binding.etUsername.requestFocus()
    }

    private fun validateTokenAndAutoLogin() {
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.btnLogin.isEnabled = false

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val userService = RetrofitClient.getClient().create(UserService::class.java)
                val username = prefsManager.username

                if (!username.isNullOrEmpty()) {
                    val response = userService.getUser(username)

                    runOnUiThread {
                        binding.progressBar.visibility = android.view.View.GONE
                        binding.btnLogin.isEnabled = true

                        if (response.isSuccessful) {
                            // Токен валиден, переходим в MainActivity
                            println("✅ Token is valid, auto-login successful")
                            startMainActivity()
                        } else {
                            // Токен невалиден, показываем экран логина
                            println("❌ Token validation failed: ${response.code()}")
                            showTokenInvalidMessage()
                            setupUI()
                        }
                    }
                } else {
                    runOnUiThread {
                        binding.progressBar.visibility = android.view.View.GONE
                        binding.btnLogin.isEnabled = true
                        setupUI()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.btnLogin.isEnabled = true
                    println("⚠️ Token validation error: ${e.message}")
                    // При ошибке сети тоже показываем экран логина
                    Toast.makeText(
                        this@LoginActivity,
                        "Ошибка сети. Войдите заново.",
                        Toast.LENGTH_SHORT
                    ).show()
                    setupUI()
                }
            }
        }
    }

    private fun showTokenInvalidMessage() {
        Toast.makeText(
            this,
            "Предыдущая сессия истекла. Пожалуйста, войдите снова.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun login(username: String, password: String) {
        binding.btnLogin.isEnabled = false
        binding.progressBar.visibility = android.view.View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val authService = RetrofitClient.getClient().create(AuthService::class.java)
                val response = authService.login(AuthRequest(username, password))

                runOnUiThread {
                    binding.btnLogin.isEnabled = true
                    binding.progressBar.visibility = android.view.View.GONE

                    if (response.isSuccessful) {
                        val authResponse = response.body()!!

                        // Просто сохраняем токен
                        prefsManager.authToken = authResponse.accessToken
                        prefsManager.username = authResponse.username
                        prefsManager.displayName = authResponse.displayName

                        // Переходим в MainActivity
                        val intent = Intent(this@LoginActivity, MainActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
                        startActivity(intent)
                        finish()
                    } else {
                        Toast.makeText(this@LoginActivity, "Ошибка входа", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.btnLogin.isEnabled = true
                    binding.progressBar.visibility = android.view.View.GONE
                    Toast.makeText(this@LoginActivity, "Ошибка сети", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    private fun startMainActivity() {
        println("🚀 Starting MainActivity")
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}