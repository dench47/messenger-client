//package com.messenger.messengerclient.utils
//
//import android.app.Application
//import com.messenger.messengerclient.MessengerApplication
//import com.messenger.messengerclient.data.model.User
//import com.messenger.messengerclient.network.RetrofitClient
//import com.messenger.messengerclient.network.service.UserService
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.Job
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.withContext
//
//object OnlineStatusManager {
//
//    private var refreshJob: Job? = null
//    private val onlineUsers = mutableSetOf<String>()
//
//    fun startMonitoring(application: Application, username: String) {
//        stopMonitoring()
//
//        refreshJob = CoroutineScope(Dispatchers.IO).launch {
//            while (true) {
//                try {
//                    updateOnlineStatus(application, username)
//                    delay(30000) // Обновляем каждые 30 секунд
//                } catch (e: Exception) {
//                    println("⚠️ Online status update failed: ${e.message}")
//                    delay(60000) // При ошибке ждем дольше
//                }
//            }
//        }
//    }
//
//    private suspend fun updateOnlineStatus(application: Application, currentUsername: String) {
//        withContext(Dispatchers.IO) {
//            try {
//                val userService = RetrofitClient.getClient().create(UserService::class.java)
//                val response = userService.getOnlineUsers()
//
//                if (response.isSuccessful) {
//                    val onlineUsernames = response.body() ?: emptyList()
//
//                    // Обновляем кэш
//                    onlineUsers.clear()
//                    onlineUsers.addAll(onlineUsernames)
//
//                    println("📊 Online users: ${onlineUsernames.size}")
//
//                    // Обновляем WebSocket соединение если нужно
//                    val app = application as? MessengerApplication
//                    if (app?.webSocketService?.isConnected() == false) {
//                        val prefsManager = PrefsManager(application)
//                        val token = prefsManager.authToken
//                        if (!token.isNullOrEmpty()) {
//                            app.webSocketService.connect(token, currentUsername)
//                        }
//                    }
//                }
//            } catch (e: Exception) {
//                throw e
//            }
//        }
//    }
//
//    fun isUserOnline(username: String): Boolean {
//        return onlineUsers.contains(username)
//    }
//
//    fun updateUserStatus(users: List<User>, currentUsername: String): List<User> {
//        return users.map { user ->
//            if (user.username == currentUsername) {
//                user.copy(online = true) // Текущий пользователь всегда онлайн
//            } else {
//                user.copy(online = isUserOnline(user.username))
//            }
//        }
//    }
//
//    fun stopMonitoring() {
//        refreshJob?.cancel()
//        refreshJob = null
//        onlineUsers.clear()
//    }
//}