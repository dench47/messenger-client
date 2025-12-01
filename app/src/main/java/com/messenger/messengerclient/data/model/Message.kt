package com.messenger.messengerclient.data.model

import com.google.gson.annotations.SerializedName

data class Message(
    @SerializedName("id")
    val id: Long? = null,

    @SerializedName("content")
    val content: String,

    @SerializedName("timestamp")
    val timestamp: String? = null,  // Принимаем как String

    @SerializedName("isRead")
    val isRead: Boolean = false,

    @SerializedName("senderUsername")
    val senderUsername: String,

    @SerializedName("receiverUsername")
    val receiverUsername: String,

    @SerializedName("type")
    val type: String = "TEXT"
) {
    fun isSentByMe(currentUser: String): Boolean {
        return senderUsername == currentUser
    }
}