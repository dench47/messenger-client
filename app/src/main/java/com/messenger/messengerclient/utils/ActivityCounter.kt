package com.messenger.messengerclient.utils

import android.util.Log

object ActivityCounter {
    private var activityCount = 0
    private val listeners = mutableListOf<(Boolean) -> Unit>()

    // НОВЫЕ ПОЛЯ для отслеживания текущего чата
    private var currentActivity: String? = null
    private var chatPartnerUsername: String? = null

    // ================================================
    // ВАШИ ОРИГИНАЛЬНЫЕ МЕТОДЫ (БЕЗ ИЗМЕНЕНИЙ)
    // ================================================

    fun activityStarted() {
        synchronized(this) {
            val oldCount = activityCount
            activityCount++
            Log.d("ActivityCounter", "Activity started: $oldCount → $activityCount")
            if (oldCount == 0 && activityCount == 1) {
                Log.d("ActivityCounter", "📱 App came to FOREGROUND")
                notifyListeners(true)
            }
        }
    }

    fun activityStopped() {
        synchronized(this) {
            val oldCount = activityCount
            activityCount--
            if (activityCount < 0) activityCount = 0
            Log.d("ActivityCounter", "Activity stopped: $oldCount → $activityCount")
            if (oldCount == 1 && activityCount == 0) {
                Log.d("ActivityCounter", "📱 App went to BACKGROUND")
                notifyListeners(false)
            }
        }
    }

    fun isAppInForeground(): Boolean = activityCount > 0

    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
        Log.d("ActivityCounter", "Listener added, total: ${listeners.size}")
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
        Log.d("ActivityCounter", "Listener removed, total: ${listeners.size}")
    }

    private fun notifyListeners(isForeground: Boolean) {
        synchronized(this) {
            Log.d("ActivityCounter", "Notifying ${listeners.size} listeners: foreground=$isForeground")
            val listenersCopy = listeners.toList()
            listenersCopy.forEach {
                try {
                    it(isForeground)
                } catch (e: Exception) {
                    Log.e("ActivityCounter", "Error in listener", e)
                }
            }
        }
    }

    fun reset() {
        Log.d("ActivityCounter", "⚠️ RESETTING counter from $activityCount to 0")
        activityCount = 0
        notifyListeners(false)
    }

    // ================================================
    // НОВЫЕ МЕТОДЫ ДЛЯ DEEP LINKING (ДОБАВЛЕНЫ)
    // ================================================

    /**
     * Обновить информацию о текущей Activity
     * Используется в onResume() каждой Activity
     */
    fun updateCurrentActivity(activityName: String? = null, chatPartner: String? = null) {
        synchronized(this) {
            currentActivity = activityName
            chatPartnerUsername = chatPartner
            Log.d("ActivityCounter", "Current activity: $activityName, chat partner: $chatPartner")
        }
    }

    /**
     * Проверить, открыт ли чат с конкретным пользователем
     * Используется в FCM сервисе для предотвращения уведомлений
     */
    fun isChatWithUserOpen(username: String?): Boolean {
        synchronized(this) {
            val isOpen = currentActivity == "ChatActivity" &&
                    username != null &&
                    username.equals(chatPartnerUsername, ignoreCase = true)

            Log.d("ActivityCounter", "Check chat with '$username': $isOpen (current: $chatPartnerUsername)")
            return isOpen
        }
    }

    /**
     * Получить текущую Activity (для отладки)
     */
    fun getCurrentActivity(): String? {
        synchronized(this) {
            return currentActivity
        }
    }

    /**
     * Получить текущего партнера по чату (для отладки)
     */
    fun getCurrentChatPartner(): String? {
        synchronized(this) {
            return chatPartnerUsername
        }
    }
}