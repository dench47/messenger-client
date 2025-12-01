package com.messenger.messengerclient.utils

import android.content.Context
import android.content.SharedPreferences
import java.util.Date

class PrefsManager(context: Context) {

    companion object {
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
        tokenExpiry = System.currentTimeMillis() + expiresIn
    }

    fun isTokenExpired(): Boolean {
        return System.currentTimeMillis() >= tokenExpiry
    }

    fun isLoggedIn(): Boolean {
        val hasToken = !authToken.isNullOrEmpty()
        val hasRefreshToken = !refreshToken.isNullOrEmpty()
        val hasUsername = !username.isNullOrEmpty()
        val tokenNotExpired = !isTokenExpired()

        println("🔐 Auth check:")
        println("  - Has token: $hasToken")
        println("  - Has refresh token: $hasRefreshToken")
        println("  - Has username: $hasUsername")
        println("  - Token not expired: $tokenNotExpired")
        println("  - Token expiry time: ${Date(tokenExpiry)}")
        println("  - Current time: ${Date()}")

        return hasToken && hasRefreshToken && hasUsername && tokenNotExpired
    }

    fun shouldRefreshToken(): Boolean {
        // Обновляем токен если до истечения осталось меньше 5 минут
        return System.currentTimeMillis() >= (tokenExpiry - 5 * 60 * 1000)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}