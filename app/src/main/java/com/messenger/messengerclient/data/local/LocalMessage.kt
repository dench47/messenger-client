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
    // 👇 ДОБАВЛЯЕМ поле status с дефолтным значением "SENT"
    val status: String = "SENT"
)