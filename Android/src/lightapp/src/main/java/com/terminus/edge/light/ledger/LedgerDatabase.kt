package com.terminus.edge.light.ledger

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
  entities = [LedgerMessageEntity::class, ConversationEntity::class, AttachmentEntity::class],
  version = 3,
  exportSchema = false,
)
abstract class LedgerDatabase : RoomDatabase() {
  abstract fun ledgerDao(): LedgerDao

  companion object {
    val MIGRATION_2_3 =
      object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversations (
              id TEXT NOT NULL PRIMARY KEY,
              title TEXT NOT NULL,
              preview TEXT NOT NULL,
              runtimeType TEXT NOT NULL,
              modelName TEXT,
              createdAt INTEGER NOT NULL,
              updatedAt INTEGER NOT NULL,
              archivedAt INTEGER
            )
            """.trimIndent()
          )
          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS attachments (
              id TEXT NOT NULL PRIMARY KEY,
              messageId TEXT NOT NULL,
              sessionId TEXT NOT NULL,
              displayName TEXT NOT NULL,
              path TEXT NOT NULL,
              sha256 TEXT NOT NULL,
              width INTEGER NOT NULL,
              height INTEGER NOT NULL
            )
            """.trimIndent()
          )
          db.execSQL(
            """
            INSERT OR IGNORE INTO conversations
              (id, title, preview, runtimeType, modelName, createdAt, updatedAt, archivedAt)
            SELECT sessionId, 'Conversation', '', 'LITERT_LM', NULL, MIN(timestamp), MAX(timestamp), NULL
            FROM messages GROUP BY sessionId
            """.trimIndent()
          )
        }
      }
  }
}
