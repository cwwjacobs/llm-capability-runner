package com.terminus.edge.light

import android.content.Context
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class HumanNote(
  val id: String = UUID.randomUUID().toString(),
  val title: String,
  val body: String,
  val updatedAtMs: Long = System.currentTimeMillis(),
  val archived: Boolean = false,
)

internal object HumanNoteCodec {
  private val json = Json { ignoreUnknownKeys = true }

  fun encode(notes: List<HumanNote>): String =
    buildJsonArray {
        notes.forEach { note ->
          add(
            buildJsonObject {
              put("id", note.id)
              put("title", note.title)
              put("body", note.body)
              put("updated_at_ms", note.updatedAtMs)
              put("archived", note.archived)
            }
          )
        }
      }
      .toString()

  fun decode(source: String?): List<HumanNote> {
    if (source.isNullOrBlank()) return emptyList()
    return runCatching {
        json.parseToJsonElement(source).jsonArray.mapNotNull { element ->
          val value = element.jsonObject
          val id = value["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
          val title = value["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
          val body = value["body"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
          HumanNote(
            id = id,
            title = title,
            body = body,
            updatedAtMs = value["updated_at_ms"]?.jsonPrimitive?.longOrNull ?: 0L,
            archived = value["archived"]?.jsonPrimitive?.contentOrNull == "true",
          )
        }
      }
      .getOrDefault(emptyList())
  }
}

class HumanNotesStore(context: Context) {
  private val preferences =
    context.getSharedPreferences("edge_light_human_notes", Context.MODE_PRIVATE)

  fun activeNotes(): List<HumanNote> =
    loadAll().filterNot(HumanNote::archived).sortedByDescending(HumanNote::updatedAtMs)

  fun save(id: String?, title: String, body: String): List<HumanNote> {
    val safeTitle = title.trim().take(MAX_TITLE_LENGTH).ifEmpty { "Untitled note" }
    val safeBody = body.trim().take(MAX_BODY_LENGTH)
    require(safeBody.isNotEmpty()) { "A note cannot be empty." }
    val all = loadAll().toMutableList()
    val existingIndex = id?.let { noteId -> all.indexOfFirst { it.id == noteId } } ?: -1
    val note =
      HumanNote(
        id = id ?: UUID.randomUUID().toString(),
        title = safeTitle,
        body = safeBody,
      )
    if (existingIndex >= 0) all[existingIndex] = note else all += note
    persist(all.takeLast(MAX_NOTES))
    return activeNotes()
  }

  fun archive(id: String): List<HumanNote> {
    val updated =
      loadAll().map { note ->
        if (note.id == id) note.copy(archived = true, updatedAtMs = System.currentTimeMillis())
        else note
      }
    persist(updated)
    return activeNotes()
  }

  private fun loadAll(): List<HumanNote> =
    HumanNoteCodec.decode(preferences.getString(KEY_NOTES, null))

  private fun persist(notes: List<HumanNote>) {
    check(preferences.edit().putString(KEY_NOTES, HumanNoteCodec.encode(notes)).commit()) {
      "Notes could not be saved."
    }
  }

  private companion object {
    const val KEY_NOTES = "notes_json"
    const val MAX_NOTES = 250
    const val MAX_TITLE_LENGTH = 120
    const val MAX_BODY_LENGTH = 20_000
  }
}
