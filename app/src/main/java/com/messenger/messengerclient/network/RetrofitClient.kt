package com.messenger.messengerclient.network

import android.content.Context
import com.messenger.messengerclient.config.ApiConfig
import com.messenger.messengerclient.config.ApiConfig.BASE_URL
import com.messenger.messengerclient.service.AuthService
import com.messenger.messengerclient.utils.PrefsManager
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    private var retrofit: Retrofit? = null
    private lateinit var prefsManager: PrefsManager
    private var isRefreshing = false

    fun initialize(context: Context) {
        prefsManager = PrefsManager(context)
    }

    private fun getAuthService(): AuthService {
        return getClient().create(AuthService::class.java)
    }

    private suspend fun refreshToken(): Boolean {
        if (isRefreshing) return false

        isRefreshing = true
        try {
            val refreshToken = prefsManager.refreshToken
            if (refreshToken.isNullOrEmpty()) {
                println("❌ No refresh token available")
                return false
            }

            println("🔄 Attempting token refresh...")
            val response = getAuthService().refreshToken(mapOf("refreshToken" to refreshToken))

            if (response.isSuccessful) {
                val authResponse = response.body()!!

                // Сохраняем новые токены
                prefsManager.saveTokens(
                    authResponse.accessToken,
                    authResponse.refreshToken,
                    authResponse.expiresIn
                )

                println("✅ Token refreshed successfully")
                return true
            } else {
                println("❌ Token refresh failed: ${response.code()}")
                if (response.code() == 401) {
                    // Refresh token тоже истек - нужно перелогиниться
                    prefsManager.clear()
                }
                return false
            }
        } catch (e: Exception) {
            println("💥 Token refresh error: ${e.message}")
            return false
        } finally {
            isRefreshing = false
        }
    }

    fun getClient(): Retrofit {
        if (retrofit == null) {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }

            val clientBuilder = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .addInterceptor(Interceptor { chain ->
                    var request = chain.request()

                    // Добавляем токен если есть
                    val token = prefsManager.authToken
                    if (!token.isNullOrEmpty()) {
                        request = request.newBuilder()
                            .header("Authorization", "Bearer $token")
                            .build()
                    }

                    var response = chain.proceed(request)

                    // Если получили 401, пробуем обновить токен
                    if (response.code == 401 && !request.url.toString().contains("/auth/")) {
                        println("⚠️ Received 401, attempting token refresh...")

                        runBlocking {
                            if (refreshToken()) {
                                // Повторяем запрос с новым токеном
                                val newToken = prefsManager.authToken
                                if (!newToken.isNullOrEmpty()) {
                                    response.close()

                                    request = request.newBuilder()
                                        .header("Authorization", "Bearer $newToken")
                                        .build()

                                    response = chain.proceed(request)
                                }
                            }
                        }
                    }

                    response
                })
                .authenticator { route, response ->
                    // Для автоматической аутентификации при 401
                    if (response.code == 401) {
                        runBlocking {
                            if (refreshToken()) {
                                val newToken = prefsManager.authToken
                                if (!newToken.isNullOrEmpty()) {
                                    return@runBlocking response.request.newBuilder()
                                        .header("Authorization", "Bearer $newToken")
                                        .build()
                                }
                            }
                        }
                    }
                    null
                }

            retrofit = Retrofit.Builder()
                .baseUrl(ApiConfig.BASE_URL)
                .client(clientBuilder.build())
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }
        return retrofit!!
    }

    fun updateAuthToken(token: String) {
        prefsManager.authToken = token
        retrofit = null // Сбрасываем для создания нового клиента
    }

    fun getClientWithAuth(token: String): Retrofit {
        val authInterceptor = Interceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
                .header("Authorization", "Bearer $token")
                .method(original.method, original.body)
                .build()
            chain.proceed(request)
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
}