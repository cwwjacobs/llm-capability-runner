package com.terminus.edge.light.ledger

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
  @PrimaryKey val id: String,
  val title: String,
  val preview: String,
  val runtimeType: String,
  val modelName: String?,
  val createdAt: Long,
  val updatedAt: Long,
  val archivedAt: Long? = null,
)

@Entity(tableName = "attachments")
data class AttachmentEntity(
  @PrimaryKey val id: String,
  val messageId: String,
  val sessionId: String,
  val displayName: String,
  val path: String,
  val sha256: String,
  val width: Int,
  val height: Int,
)
