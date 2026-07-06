package com.terminus.edge.light.ledger

import android.content.Context
import androidx.room.Room
import com.terminus.edge.light.MessageRole
import com.terminus.edge.light.UiMessage
import com.terminus.edge.light.context.RetentionPolicy
import com.terminus.edge.light.trace.ReviewDecision
import com.terminus.edge.light.image.ImageAttachmentLoader
import java.io.File

class ConversationLedger(context: Context) {
  private val appContext = context.applicationContext
  private val attachmentRoot = File(appContext.filesDir, "conversation_attachments")
  private val db =
    Room.databaseBuilder(appContext, LedgerDatabase::class.java, "conversation_ledger.db")
      .addMigrations(LedgerDatabase.MIGRATION_2_3)
      .build()
  private val dao = db.ledgerDao()

  suspend fun getSessionIds(): List<String> = dao.getSessionIds()

  suspend fun getConversations(): List<ConversationEntity> = dao.getConversations()

  suspend fun loadMessagesForSession(sessionId: String): List<UiMessage> {
    val attachments = dao.getAttachmentsForSession(sessionId).associateBy(AttachmentEntity::messageId)
    return dao.getMessagesForSession(sessionId).map { entity ->
      val attachment = attachments[entity.id]
      UiMessage(
        id = entity.id,
        role = MessageRole.valueOf(entity.role),
        content = entity.content,
        traceId = entity.traceId,
        reviewDecision = entity.reviewDecision?.let { ReviewDecision.valueOf(it) },
        retentionPolicy = RetentionPolicy.valueOf(entity.retentionPolicy),
        image =
          attachment?.let {
            runCatching {
                ImageAttachmentLoader.load(File(it.path), it.id, it.displayName)
              }
              .getOrNull()
          },
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
    message.image?.let { image ->
      val sessionRoot = File(attachmentRoot, sessionId).apply { mkdirs() }
      val imageFile = File(sessionRoot, "${image.id}.png")
      if (!imageFile.exists()) imageFile.writeBytes(image.pngBytes)
      dao.insertOrUpdate(
        AttachmentEntity(
          id = image.id,
          messageId = message.id,
          sessionId = sessionId,
          displayName = image.displayName,
          path = imageFile.absolutePath,
          sha256 = image.sha256,
          width = image.width,
          height = image.height,
        )
      )
    }
  }

  suspend fun upsertConversation(
    sessionId: String,
    messages: List<UiMessage>,
    runtimeType: String,
    modelName: String?,
    timestamp: Long = System.currentTimeMillis(),
  ) {
    val existing = dao.getConversation(sessionId)
    val firstUser = messages.firstOrNull { it.role == MessageRole.USER }?.content.orEmpty()
    val title = firstUser.lineSequence().firstOrNull().orEmpty().trim().take(52).ifBlank { "New conversation" }
    val preview = messages.lastOrNull { it.content.isNotBlank() }?.content.orEmpty().replace('\n', ' ').take(96)
    dao.insertOrUpdate(
      ConversationEntity(
        id = sessionId,
        title = if (existing?.title == "Conversation" || existing == null) title else existing.title,
        preview = preview,
        runtimeType = runtimeType,
        modelName = modelName,
        createdAt = existing?.createdAt ?: timestamp,
        updatedAt = timestamp,
        archivedAt = existing?.archivedAt,
      )
    )
  }

  suspend fun archive(sessionId: String) {
    val source = File(attachmentRoot, sessionId)
    if (source.exists()) {
      val archiveRoot = File(appContext.filesDir, "conversation_archive").apply { mkdirs() }
      val target = File(archiveRoot, "$sessionId-${System.currentTimeMillis()}")
      try {
        java.nio.file.Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
      } catch (_: Exception) {
        java.nio.file.Files.move(source.toPath(), target.toPath())
      }
    }
    dao.archiveConversation(sessionId, System.currentTimeMillis())
  }
}
