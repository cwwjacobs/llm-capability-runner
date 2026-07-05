package com.terminus.edge.light.ledger

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
@JvmSuppressWildcards
interface LedgerDao {
  @Query("SELECT DISTINCT sessionId FROM messages ORDER BY timestamp DESC")
  suspend fun getSessionIds(): List<String>

  @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
  suspend fun getMessagesForSession(sessionId: String): List<LedgerMessageEntity>

  @Query("SELECT * FROM messages ORDER BY timestamp ASC")
  suspend fun getAllMessages(): List<LedgerMessageEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertOrUpdate(message: LedgerMessageEntity): Long

  @Query("DELETE FROM messages")
  suspend fun deleteAll(): Int
}
