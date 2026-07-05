package com.terminus.edge.light.ledger

import android.content.Context
import androidx.room.Room
import com.terminus.edge.light.MessageRole
import com.terminus.edge.light.UiMessage
import com.terminus.edge.light.context.RetentionPolicy
import com.terminus.edge.light.trace.ReviewDecision

class ConversationLedger(context: Context) {
  private val db =
    Room.databaseBuilder(context, LedgerDatabase::class.java, "conversation_ledger.db")
      .fallbackToDestructiveMigration()
      .build()
  private val dao = db.ledgerDao()

  suspend fun getSessionIds(): List<String> = dao.getSessionIds()

  suspend fun loadMessagesForSession(sessionId: String): List<UiMessage> {
    return dao.getMessagesForSession(sessionId).map { entity ->
      UiMessage(
        id = entity.id,
        role = MessageRole.valueOf(entity.role),
        content = entity.content,
        traceId = entity.traceId,
        reviewDecision = entity.reviewDecision?.let { ReviewDecision.valueOf(it) },
        retentionPolicy = RetentionPolicy.valueOf(entity.retentionPolicy)
      )
    }
  }

  suspend fun saveMessage(message: UiMessage, sessionId: String, timestamp: Long = System.currentTimeMillis()) {
    dao.insertOrUpdate(
      LedgerMessageEntity(
        id = message.id,
        role = message.role.name,
        content = message.content,
        traceId = message.traceId,
        reviewDecision = message.reviewDecision?.name,
        retentionPolicy = message.retentionPolicy.name,
        timestamp = timestamp,
        sessionId = sessionId
      )
    )
  }

  suspend fun deleteAll() {
    dao.deleteAll()
  }
}
