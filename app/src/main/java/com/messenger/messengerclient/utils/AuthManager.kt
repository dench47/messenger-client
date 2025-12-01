//package com.messenger.messengerclient.utils
//
//import android.content.Context
//import android.content.Intent
//import androidx.appcompat.app.AppCompatActivity
//import com.messenger.messengerclient.ui.LoginActivity
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.withContext
//
//object AuthManager {
//
//    suspend fun logout(
//        context: Context,
//        prefsManager: PrefsManager,
//        userService: com.messenger.messengerclient.network.service.UserService? = null
//    ): Boolean {
//        return withContext(Dispatchers.IO) {
//            try {
//                // 1. Отправляем logout на сервер (если сервис предоставлен)
//                userService?.let { service ->
//                    val username = prefsManager.username
//                    if (!username.isNullOrEmpty()) {
//                        try {
//                            val request = mapOf("username" to username)
//                            service.logout(request)
//                            println("📡 Server logout successful")
//                        } catch (e: Exception) {
//                            println("⚠️ Server logout failed (ignored): ${e.message}")
//                            // Игнорируем ошибки сервера, все равно выходим локально
//                        }
//                    }
//                }
//
//                // 2. Очищаем локальные данные
//                prefsManager.clear()
//                println("🗑️ Local data cleared")
//
//                // 3. Отключаем WebSocket в главном потоке (безопасно)
//                withContext(Dispatchers.Main) {
//                    try {
//                        val app = context.applicationContext as? com.messenger.messengerclient.MessengerApplication
//                        app?.webSocketService?.disconnect()
//                        println("🔌 WebSocket disconnected")
//                    } catch (e: Exception) {
//                        println("⚠️ WebSocket disconnect error (ignored): ${e.message}")
//                        // Игнорируем ошибки отключения WebSocket
//                    }
//                }
//
//                true
//            } catch (e: Exception) {
//                println("💥 Error in AuthManager.logout: ${e.message}")
//
//                // Даже при ошибке очищаем локальные данные
//                try {
//                    prefsManager.clear()
//                } catch (e2: Exception) {
//                    println("💥💥 Critical: Failed to clear prefs: ${e2.message}")
//                }
//
//                false
//            }
//        }
//    }
//
//    fun redirectToLogin(activity: AppCompatActivity) {
//        val intent = Intent(activity, LoginActivity::class.java).apply {
//            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
//        }
//        activity.startActivity(intent)
//        activity.finish()
//    }
//}