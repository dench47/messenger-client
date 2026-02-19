package com.messenger.messengerclient.data.model

data class Conversation(
    val user: User,
    val lastMessage: Message? = null,
    val unreadCount: Int = 0,
    val lastMessageTime: String? = null
)