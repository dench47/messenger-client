package com.messenger.messengerclient.data.model

import com.google.gson.annotations.SerializedName

data class Message(
    @SerializedName("id")
    val id: Long? = null,

    @SerializedName("content")
    val content: String,

    @SerializedName("timestamp")
    val timestamp: String? = null,

    @SerializedName("isRead")
    val isRead: Boolean = false,

    @SerializedName("senderUsername")
    val senderUsername: String,

    @SerializedName("receiverUsername")
    val receiverUsername: String,

    @SerializedName("type")
    val type: String = "TEXT",

    // 👇 ДОБАВЛЯЕМ поле status (соответствует тому что приходит с сервера)
    @SerializedName("status")
    val status: String = "SENT"
) {
    fun isSentByMe(currentUser: String): Boolean {
        return senderUsername == currentUser
    }

    fun getMessageStatus(): MessageStatus {
        return try {
            MessageStatus.valueOf(status.uppercase())
        } catch (e: Exception) {
            MessageStatus.SENT
        }
    }
}