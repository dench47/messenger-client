package com.messenger.messengerclient.utils

import android.util.Log

object ActivityCounter {
    private var activityCount = 0
    private val listeners = mutableListOf<(Boolean) -> Unit>() // ← СОХРАНЯЕМ!

    // НОВЫЕ ПОЛЯ для отслеживания текущего чата
    private var currentActivity: String? = null
    private var lastChatPartner: String? = null
    private var currentActivityName: String? = null // ← ДОБАВЬ ЭТО!

    // ДОБАВЛЯЮ для задержки
    private var backgroundHandler: android.os.Handler? = null
    private var backgroundRunnable: Runnable? = null
    private const val BACKGROUND_DELAY = 500L // 0.5 секунды

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

                // ТОЧНО ТАК ЖЕ КАК В onTaskRemoved() ПРИ СВАЙПЕ:
                // 1. Сбрасываем счетчик как в reset()
                activityCount = 0                     // ← ДОБАВИТЬ ЭТУ СТРОКУ

                // 2. Уведомляем слушателей (отправит ACTION_APP_BACKGROUND)
                notifyListeners(false)

                // БОЛЬШЕ НИЧЕГО!
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

    fun clearListeners() {
        synchronized(this) {
            listeners.clear()
            Log.d("ActivityCounter", "🗑️ Cleared all listeners (was: ${listeners.size})")
        }
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
    // НОВЫЕ МЕТОДЫ ДЛЯ УВЕДОМЛЕНИЙ
    // ================================================

    /**
     * Обновить информацию о текущей Activity
     * Используется в onResume() каждой Activity
     */
    fun updateCurrentActivity(activityName: String? = null, chatPartner: String? = null) {
        synchronized(this) {
            currentActivity = activityName
            currentActivityName = activityName // ← ОБНОВЛЯЕМ currentActivityName тоже!
            if (chatPartner != null) {
                lastChatPartner = chatPartner
                Log.d("ActivityCounter", "💾 Last chat partner: $chatPartner")
            }
        }
    }

    /**
     * Проверить, нужно ли блокировать уведомление
     * Возвращает true если:
     * 1. Приложение активно (не в фоне)
     * 2. Текущая Activity - ChatActivity
     * 3. И чат открыт именно с этим пользователем
     */
    fun isChatWithUserOpen(username: String?): Boolean {
        synchronized(this) {
            val isAppInForeground = activityCount > 0
            val isCurrentlyInChat = currentActivity == "ChatActivity"
            val isChatWithSender = username != null && username == lastChatPartner

            val shouldBlockNotification = isAppInForeground && isCurrentlyInChat && isChatWithSender

            Log.d("ActivityCounter", "🔔 Check notifications for '$username':")
            Log.d("ActivityCounter", "  App in foreground: $isAppInForeground")
            Log.d("ActivityCounter", "  Current activity: $currentActivity")
            Log.d("ActivityCounter", "  Last chat partner: $lastChatPartner")
            Log.d("ActivityCounter", "  BLOCK notification? $shouldBlockNotification")

            return shouldBlockNotification
        }
    }

    /**
     * Очистить lastChatPartner (при смене чата или logout)
     */
    fun clearLastChatPartner() {
        synchronized(this) {
            lastChatPartner = null
            Log.d("ActivityCounter", "🗑️ Last chat partner cleared")
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
     * Получить последнего партнера по чату (для отладки)
     */
    fun getLastChatPartner(): String? {
        synchronized(this) {
            return lastChatPartner
        }
    }

    /**
     * Проверить, находимся ли в звонке
     */
    fun isInCall(): Boolean {
        synchronized(this) {
            return currentActivityName == "CallActivity" // ← Теперь работает!
        }
    }
}