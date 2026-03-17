package com.messenger.messengerclient.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class LocalMessage(
    @PrimaryKey
    val id: Long = 0,
    val senderUsername: String,
    val receiverUsername: String,
    val content: String,
    val timestamp: String,
    val isRead: Boolean,
    val status: String = "SENT"
) {
    // 👇 ВАЖНО: обновляет и статус, и isRead
    fun withStatus(newStatus: String): LocalMessage {
        return this.copy(
            status = newStatus,
            isRead = newStatus == "READ" || this.isRead
        )
    }
}