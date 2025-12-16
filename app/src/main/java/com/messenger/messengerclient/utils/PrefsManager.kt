package com.messenger.messengerclient.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.Date

class PrefsManager(context: Context) {

    companion object {
        private const val TAG = "PrefsManager"
        private const val PREFS_NAME = "messenger_prefs"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_USERNAME = "username"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_TOKEN_EXPIRY = "token_expiry"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var authToken: String?
        get() = prefs.getString(KEY_AUTH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_AUTH_TOKEN, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    var displayName: String?
        get() = prefs.getString(KEY_DISPLAY_NAME, null)
        set(value) = prefs.edit().putString(KEY_DISPLAY_NAME, value).apply()

    var tokenExpiry: Long
        get() = prefs.getLong(KEY_TOKEN_EXPIRY, 0)
        set(value) = prefs.edit().putLong(KEY_TOKEN_EXPIRY, value).apply()

    fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long) {
        authToken = accessToken
        this.refreshToken = refreshToken

        // expiresIn - миллисекунды от сервера (3600000 = 1 час)
        tokenExpiry = System.currentTimeMillis() + expiresIn

        Log.d(TAG, "💾 Tokens saved:")
        Log.d(TAG, "  - Username: $username")
        Log.d(TAG, "  - Access token length: ${accessToken.length}")
        Log.d(TAG, "  - Refresh token length: ${refreshToken.length}")
        Log.d(TAG, "  - Expires in (from server): ${expiresIn}ms (${expiresIn / 1000}s)")
        Log.d(TAG, "  - Token will expire at: ${Date(tokenExpiry)}")
        Log.d(TAG, "  - Current time: ${Date()}")
        Log.d(TAG, "  - Time left: ${expiresIn / 1000} seconds")
    }

    fun isTokenExpired(): Boolean {
        val expired = System.currentTimeMillis() >= tokenExpiry
        Log.d(TAG, "🔍 isTokenExpired(): $expired (tokenExpiry: $tokenExpiry, current: ${System.currentTimeMillis()})")
        return expired
    }

    fun isLoggedIn(): Boolean {
        val hasToken = !authToken.isNullOrEmpty()
        val hasRefreshToken = !refreshToken.isNullOrEmpty()
        val hasUsername = !username.isNullOrEmpty()
        val hasExpiry = tokenExpiry > 0

        Log.d(TAG, "🔐 Auth check:")
        Log.d(TAG, "  - Has access token: $hasToken")
        Log.d(TAG, "  - Has refresh token: $hasRefreshToken")
        Log.d(TAG, "  - Has username: $hasUsername ($username)")
        Log.d(TAG, "  - Has expiry time: $hasExpiry")
        Log.d(TAG, "  - Token expiry value: $tokenExpiry")

        if (!hasToken || !hasRefreshToken || !hasUsername) {
            Log.e(TAG, "  ❌ Missing basic auth data")
            return false
        }

        if (hasExpiry) {
            val currentTime = System.currentTimeMillis()
            val tokenValid = currentTime < tokenExpiry
            val timeLeft = tokenExpiry - currentTime

            Log.d(TAG, "  - Token expiry date: ${Date(tokenExpiry)}")
            Log.d(TAG, "  - Current date: ${Date(currentTime)}")
            Log.d(TAG, "  - Time left: ${timeLeft / 1000} seconds")
            Log.d(TAG, "  - Token valid: $tokenValid")

            if (!tokenValid) {
                Log.w(TAG, "  ⚠️ Token expired but we have refresh token")
                // Токен истек, но у нас есть refresh token
                // Пользователь все еще считается авторизованным
                // RetrofitClient обновит токен автоматически
                return true
            }

            return true
        }

        // Если время истечения не установлено (старая версия)
        Log.w(TAG, "  ⚠️ No expiry time set, assuming token is valid")
        return true
    }

    fun shouldRefreshToken(): Boolean {
        // Обновляем токен если до истечения осталось меньше 1 минуты
        val shouldRefresh = System.currentTimeMillis() >= (tokenExpiry - 1 * 60 * 1000)
        Log.d(TAG, "🔍 shouldRefreshToken(): $shouldRefresh")
        return shouldRefresh
    }

    fun clear() {
        Log.d(TAG, "🗑️ Clearing all preferences")
        prefs.edit().clear().apply()
        Log.d(TAG, "✅ Preferences cleared")
    }

    // Дополнительный метод для отладки
    fun dumpAllPrefs() {
        Log.d(TAG, "📋 DUMP ALL PREFERENCES:")
        prefs.all.forEach { (key, value) ->
            Log.d(TAG, "  - $key: $value (${value?.javaClass?.simpleName})")
        }
    }
}