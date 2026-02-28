package com.messenger.messengerclient.utils

import com.messenger.messengerclient.data.model.MessageStatus
import java.util.concurrent.ConcurrentHashMap

object MessageStatusManager {

    private val messageStatuses = ConcurrentHashMap<Long, MessageStatus>()
    private val listeners = mutableListOf<StatusListener>()

    interface StatusListener {
        fun onStatusChanged(messageId: Long, newStatus: MessageStatus)
    }

    fun getStatus(messageId: Long): MessageStatus {
        return messageStatuses[messageId] ?: MessageStatus.SENT
    }

    fun updateStatus(messageId: Long, newStatus: MessageStatus) {
        val oldStatus = messageStatuses[messageId]
        if (oldStatus != newStatus) {
            messageStatuses[messageId] = newStatus
            listeners.forEach { it.onStatusChanged(messageId, newStatus) }
        }
    }

    fun addListener(listener: StatusListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: StatusListener) {
        listeners.remove(listener)
    }

    fun clearStatuses(messageIds: List<Long>) {
        messageIds.forEach { messageStatuses.remove(it) }
    }
}