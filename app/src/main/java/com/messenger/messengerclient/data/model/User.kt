package com.messenger.messengerclient.data.model

import java.time.LocalDateTime

data class User(
    val id: Long? = null,
    val username: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val online: Boolean = false,
    val lastSeen: String? = null,  // Изменили на String
    val createdAt: String? = null   // Изменили на String
)