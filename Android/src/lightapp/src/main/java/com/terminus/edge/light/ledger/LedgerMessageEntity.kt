package com.terminus.edge.light.ledger

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class LedgerMessageEntity(
  @PrimaryKey val id: String,
  val role: String,
  val content: String,
  val traceId: String?,
  val reviewDecision: String?,
  val retentionPolicy: String,
  val timestamp: Long,
  val sessionId: String
)
