package com.messenger.messengerclient.utils

import android.util.Log

object ActivityCounter {
    private var activityCount = 0
    private val listeners = mutableListOf<(Boolean) -> Unit>() // true = app in foreground

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
            val listenersCopy = listeners.toList() // Копируем чтобы избежать ConcurrentModification
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
        // Оповещаем что приложение точно в фоне
        notifyListeners(false)
    }
}