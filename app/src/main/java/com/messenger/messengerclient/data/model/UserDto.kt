package com.messenger.messengerclient.data.model

import com.google.gson.annotations.SerializedName

data class UserDto(
    val id: Long,
    val username: String,
    val displayName: String?,
    val avatarUrl: String?,
    val online: Boolean,
    @SerializedName("lastSeenText")
    val lastSeenText: String?
) {
    // Преобразование в обычного User (если нужно)
    fun toUser(): User = User(
        id = id,
        username = username,
        displayName = displayName,
        avatarUrl = avatarUrl,
        status = if (online) "online" else "offline",
        lastSeenText = lastSeenText
    )
}