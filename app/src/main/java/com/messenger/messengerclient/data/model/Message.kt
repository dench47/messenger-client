package com.messenger.messengerclient.data.model

import com.google.gson.annotations.SerializedName

data class Message(
    val id: Long? = null,
    val content: String,
    val senderUsername: String,
    val receiverUsername: String,
    val timestamp: String,
    val isRead: Boolean = false,
    val type: String = "TEXT",
    // 👇 НОВОЕ ПОЛЕ - статус сообщения с дефолтным значением
    val status: String = "SENT"
) {
    // Для отладки
    override fun toString(): String {
        return "Message(id=$id, from=$senderUsername, to=$receiverUsername, content=$content, status=$status)"
    }
}