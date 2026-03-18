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


        if (!hasToken || !hasRefreshToken || !hasUsername) {
            Log.e(TAG, "  ❌ Missing basic auth data")
            return false
        }

        if (hasExpiry) {
            val currentTime = System.currentTimeMillis()
            val tokenValid = currentTime < tokenExpiry
            val timeLeft = tokenExpiry - currentTime


            if (!tokenValid) {

                return true
            }

            return true
        }


        return true
    }

    fun shouldRefreshToken(): Boolean {
        val shouldRefresh = System.currentTimeMillis() >= (tokenExpiry - 1 * 60 * 1000)
        return shouldRefresh
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    // Дополнительный метод для отладки
    fun dumpAllPrefs() {
        prefs.all.forEach { (key, value) ->
        }
    }
}