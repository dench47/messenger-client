package com.messenger.messengerclient.data.model

import com.google.gson.annotations.SerializedName

// Для обычного пользователя (при поиске, добавлении)
data class User(
    val id: Long? = null,
    val username: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val status: String? = null,
    @SerializedName("lastSeenText")
    val lastSeenText: String? = null,
    val lastSeen: String? = null,
    val createdAt: String? = null
) {
    val isOnline: Boolean
        get() = status == "online" || lastSeenText == "online"
}

// DTO для контактов (приходит с сервера)
data class ContactDto(
    val id: Long,
    val username: String,
    val displayName: String?,
    val avatarUrl: String?,
    val online: Boolean,
    val status: String,
    @SerializedName("lastSeenText")
    val lastSeenText: String
) {
    // Для совместимости с существующим кодом
    fun toUser(): User = User(
        id = id,
        username = username,
        displayName = displayName,
        avatarUrl = avatarUrl,
        status = status,
        lastSeenText = lastSeenText
    )
}