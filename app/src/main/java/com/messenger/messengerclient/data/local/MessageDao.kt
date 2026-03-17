package com.messenger.messengerclient.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: LocalMessage)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllMessages(messages: List<LocalMessage>)

    @Query("SELECT * FROM messages WHERE (senderUsername = :user1 AND receiverUsername = :user2) OR (senderUsername = :user2 AND receiverUsername = :user1) ORDER BY timestamp ASC")
    suspend fun getConversation(user1: String, user2: String): List<LocalMessage>

    @Query("SELECT * FROM messages WHERE receiverUsername = :username AND isRead = 0")
    suspend fun getUnreadMessages(username: String): List<LocalMessage>

    @Query("UPDATE messages SET isRead = 1 WHERE senderUsername = :sender AND receiverUsername = :receiver")
    suspend fun markAsRead(sender: String, receiver: String)

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: Long): LocalMessage?

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: Long, status: String)

    @Query("UPDATE messages SET status = :status, isRead = :isRead WHERE id = :messageId")
    suspend fun updateMessageStatusAndRead(messageId: Long, status: String, isRead: Boolean)

    @Query("SELECT id FROM messages WHERE receiverUsername = :receiver AND senderUsername = :sender AND status != 'READ'")
    suspend fun getUnreadMessageIdsFromSender(receiver: String, sender: String): List<Long>


}