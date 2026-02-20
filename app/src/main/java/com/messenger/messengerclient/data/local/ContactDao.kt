package com.messenger.messengerclient.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ContactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContacts(contacts: List<LocalContact>)

    @Query("SELECT * FROM contacts WHERE ownerUsername = :owner")
    suspend fun getContacts(owner: String): List<LocalContact>

    @Query("DELETE FROM contacts WHERE ownerUsername = :owner")
    suspend fun deleteContacts(owner: String)
}