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

    // 👇 ВАЖНО: копирует объект с обновленным статусом И isRead
    fun withStatus(newStatus: String): Message {
        return this.copy(
            status = newStatus,
            isRead = newStatus == "READ" || this.isRead  // если статус READ, то isRead = true
        )
    }
}