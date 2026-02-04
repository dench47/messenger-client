package com.messenger.messengerclient.data.model

data class User(
    val id: Long? = null,
    val username: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val status: String? = null, // ТОЛЬКО: "online" или "offline"
    val lastSeenText: String? = null, // "online" или "Был в ЧЧ:ММ"
    val lastSeen: String? = null,
    val createdAt: String? = null
) {
    // Computed property для удобства
    val isOnline: Boolean
        get() = status == "online" || lastSeenText == "online"

    // Для DiffUtil (опционально)
    val online: Boolean @Deprecated("Use isOnline instead")
    get() = isOnline
}