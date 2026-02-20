package com.messenger.messengerclient.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class LocalContact(
    @PrimaryKey
    val id: Long,
    val username: String,
    val displayName: String?,
    val avatarUrl: String?,
    val status: String?,
    val lastSeenText: String?,
    val ownerUsername: String  // для фильтрации по пользователю
)