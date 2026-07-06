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

  @Query("SELECT * FROM conversations WHERE archivedAt IS NULL ORDER BY updatedAt DESC")
  suspend fun getConversations(): List<ConversationEntity>

  @Query("SELECT * FROM conversations WHERE id = :sessionId LIMIT 1")
  suspend fun getConversation(sessionId: String): ConversationEntity?

  @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
  suspend fun getMessagesForSession(sessionId: String): List<LedgerMessageEntity>

  @Query("SELECT * FROM messages ORDER BY timestamp ASC")
  suspend fun getAllMessages(): List<LedgerMessageEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertOrUpdate(message: LedgerMessageEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertOrUpdate(conversation: ConversationEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertOrUpdate(attachment: AttachmentEntity): Long

  @Query("SELECT * FROM attachments WHERE sessionId = :sessionId")
  suspend fun getAttachmentsForSession(sessionId: String): List<AttachmentEntity>

  @Query("UPDATE conversations SET archivedAt = :archivedAt WHERE id = :sessionId")
  suspend fun archiveConversation(sessionId: String, archivedAt: Long): Int
}
