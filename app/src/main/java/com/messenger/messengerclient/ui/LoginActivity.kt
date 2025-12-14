package com.messenger.messengerclient.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.messenger.messengerclient.MainActivity
import com.messenger.messengerclient.data.model.AuthRequest
import com.messenger.messengerclient.databinding.ActivityLoginBinding
import com.messenger.messengerclient.network.RetrofitClient
import com.messenger.messengerclient.service.AuthService
import com.messenger.messengerclient.service.UserService
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

        // Fallback проверка (на случай прямого открытия LoginActivity)
        if (prefsManager.isLoggedIn()) {
            println("⚠️ LoginActivity opened but user is logged in, redirecting to MainActivity")
            startMainActivity()
            return
        }

        setupUI()
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

                        // ОТЛАДОЧНАЯ ИНФОРМАЦИЯ
                        println("✅ Login successful:")
                        println("  - Username: ${authResponse.username}")
                        println("  - Display name: ${authResponse.displayName}")
                        println("  - Access token: ${authResponse.accessToken.take(10)}...")
                        println("  - Refresh token: ${authResponse.refreshToken.take(10)}...")
                        println("  - Expires in: ${authResponse.expiresIn} ms")
                        println("  - That's ${authResponse.expiresIn / 1000} seconds")
                        println("  - That's ${authResponse.expiresIn / (1000 * 60)} minutes")

                        // СОХРАНЕНИЕ ТОКЕНОВ С ВРЕМЕНЕМ ИСТЕЧЕНИЯ
                        prefsManager.saveTokens(
                            authResponse.accessToken,
                            authResponse.refreshToken,
                            authResponse.expiresIn // уже в миллисекундах!
                        )
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