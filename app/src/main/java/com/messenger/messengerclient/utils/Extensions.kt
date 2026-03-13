package com.messenger.messengerclient.utils

import com.messenger.messengerclient.data.local.LocalMessage
import com.messenger.messengerclient.data.model.Message

fun LocalMessage.toMessage(): Message = Message(
    id = this.id,
    senderUsername = this.senderUsername,
    receiverUsername = this.receiverUsername,
    content = this.content,
    timestamp = this.timestamp,
    isRead = this.isRead,
    status = this.status  // 👈 ДОБАВЬ!

)

fun Message.toLocal(): LocalMessage = LocalMessage(
    id = this.id ?: 0,
    senderUsername = this.senderUsername,
    receiverUsername = this.receiverUsername,
    content = this.content,
    timestamp = this.timestamp ?: "",
    isRead = this.isRead,
    status = this.status  // 👈 ДОБАВЬ!

)