//package com.messenger.messengerclient
//
//import android.app.Application
//import com.messenger.messengerclient.network.RetrofitClient
//import com.messenger.messengerclient.websocket.WebSocketService
//
//class MessengerApplication : Application() {
//
//    val webSocketService by lazy { WebSocketService() }
//
//    override fun onCreate() {
//        super.onCreate()
//
//        RetrofitClient.initialize(this)
//
//        // Настройка обработки необработанных исключений
//        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
//            println("💥 UNCAUGHT EXCEPTION in thread ${thread.name}: ${throwable.message}")
//            throwable.printStackTrace()
//        }
//
//        println("🚀 MessengerApplication initialized")
//    }
//}