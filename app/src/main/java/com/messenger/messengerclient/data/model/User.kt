package com.messenger.messengerclient.data.model

data class User(
    val id: Long? = null,
    val username: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val online: Boolean = false,
    val status: String? = null, // "online", "active", "offline", "inactive"
    val lastSeenText: String? = null, // "2 minutes ago", "just now"
    val lastSeen: String? = null,
    val createdAt: String? = null
)