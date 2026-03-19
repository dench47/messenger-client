package com.messenger.messengerclient.utils

import android.os.Handler
import android.os.Looper
import android.util.Log

object ActivityCounter {
    private var activityCount = 0
    private val listeners = mutableListOf<(Boolean) -> Unit>()

    // Текущий Activity
    private var currentActivity: String? = null
    private var lastChatPartner: String? = null
    private var currentActivityName: String? = null

    // Флаги для определения переходов
    private var isTransitionBetweenActivities = false
    private var transitionStartTime = 0L
    private const val TRANSITION_TIMEOUT = 500L // 0.5 секунды

    // Handler для задержек
    private val handler = Handler(Looper.getMainLooper())
    private var backgroundRunnable: Runnable? = null
    private const val BACKGROUND_DELAY = 500L

    // ================================================
    // ИСПРАВЛЕННЫЕ МЕТОДЫ
    // ================================================

    fun activityStarted(activityName: String? = null) {
        synchronized(this) {
            val oldCount = activityCount
            activityCount++

            // Обновляем информацию о текущей Activity
            if (activityName != null) {
                currentActivity = activityName
                currentActivityName = activityName
            }

            Log.d("ActivityCounter", "Activity started: $oldCount → $activityCount (${activityName ?: "unknown"})")

            // Отменяем отложенный переход в фон
            handler.removeCallbacksAndMessages(null)
            backgroundRunnable = null

            // Если это был переход между Activity, НЕ отправляем статус
            if (isTransitionBetweenActivities) {
                val transitionDuration = System.currentTimeMillis() - transitionStartTime
                if (transitionDuration < TRANSITION_TIMEOUT) {
                    Log.d("ActivityCounter", "🔄 Transition completed in ${transitionDuration}ms (ignoring status update)")
                    isTransitionBetweenActivities = false
                    return // НЕ отправляем статус!
                }
                isTransitionBetweenActivities = false
            }

            if (oldCount == 0 && activityCount == 1) {
                Log.d("ActivityCounter", "📱 App came to FOREGROUND")
                notifyListeners(true)
            }
        }
    }

    fun activityStopped(activityName: String? = null) {
        synchronized(this) {
            val oldCount = activityCount
            activityCount--
            if (activityCount < 0) activityCount = 0

            Log.d("ActivityCounter", "Activity stopped: $oldCount → $activityCount (${activityName ?: "unknown"})")

            if (oldCount == 1 && activityCount == 0) {
                Log.d("ActivityCounter", "📱 Possible BACKGROUND transition")

                // Отменяем предыдущую задачу
                handler.removeCallbacksAndMessages(null)

                // Запускаем с задержкой
                backgroundRunnable = Runnable {
                    synchronized(this) {
                        // Проверяем все еще в фоне?
                        if (activityCount == 0) {
                            Log.d("ActivityCounter", "📱 Confirmed BACKGROUND (after delay)")
                            activityCount = 0
                            notifyListeners(false)
                        }
                    }
                }

                handler.postDelayed(backgroundRunnable!!, BACKGROUND_DELAY)
            }
        }
    }

    // НОВЫЙ МЕТОД: Пометить начало перехода между Activity
    fun startActivityTransition(toActivity: String? = null) {
        synchronized(this) {
            isTransitionBetweenActivities = true
            transitionStartTime = System.currentTimeMillis()

            if (toActivity != null) {
                currentActivity = toActivity
                currentActivityName = toActivity
            }

            Log.d("ActivityCounter", "🔄 Transition started to: ${toActivity ?: "unknown"}")
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
    // ОСТАЛЬНЫЕ МЕТОДЫ (без изменений)
    // ================================================

    fun updateCurrentActivity(activityName: String? = null, chatPartner: String? = null) {
        synchronized(this) {
            currentActivity = activityName
            currentActivityName = activityName
            if (chatPartner != null) {
                lastChatPartner = chatPartner
                Log.d("ActivityCounter", "💾 Last chat partner: $chatPartner")
            }
        }
    }

    fun isChatWithUserOpen(username: String?): Boolean {
        synchronized(this) {
            val isAppInForeground = activityCount > 0
            val isCurrentlyInChat = currentActivity == "ChatActivity"
            val isChatWithSender = username != null && username == lastChatPartner
            val shouldBlockNotification = isAppInForeground && isCurrentlyInChat && isChatWithSender
            return shouldBlockNotification
        }
    }

    fun clearLastChatPartner() {
        synchronized(this) {
            lastChatPartner = null
            Log.d("ActivityCounter", "🗑️ Last chat partner cleared")
        }
    }

    fun getCurrentActivity(): String? {
        synchronized(this) {
            return currentActivity
        }
    }

    fun getLastChatPartner(): String? {
        synchronized(this) {
            return lastChatPartner
        }
    }

    fun isInCall(): Boolean {
        synchronized(this) {
            return currentActivityName == "CallActivity"
        }
    }
}