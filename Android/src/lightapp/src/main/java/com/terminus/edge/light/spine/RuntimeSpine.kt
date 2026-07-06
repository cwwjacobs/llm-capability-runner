package com.terminus.edge.light.spine

import com.terminus.edge.light.trace.TraceIntegrity
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class SpineRecordType(val wireValue: String) {
  TRACE("trace"),
  FAILURE_TRACE("failure_trace"),
  CORRECTION_TRACE("correction_trace"),
  CONTINUITY_LOG("continuity_log"),
  PROVENANCE("provenance"),
  TRAINING_TRACE("training_trace");

  companion object {
    fun fromWire(value: String?): SpineRecordType? = entries.firstOrNull { it.wireValue == value }
  }
}

data class SpineRecord(
  val eventId: String,
  val type: SpineRecordType,
  val occurredAtMs: Long,
  val sessionId: String?,
  val traceId: String?,
  val parentEventId: String?,
  val payload: JsonObject,
  val provenance: JsonObject,
  val previousHash: String?,
  val eventHash: String,
  val integrityValid: Boolean,
  val raw: String,
)

data class SpineReadResult(
  val records: List<SpineRecord>,
  val malformedLines: Int,
  val legacyRecords: Int,
  val integrityValid: Boolean,
)

class RuntimeSpine(
  private val file: File,
  private val legacyTraceFile: File? = null,
  private val archiveRoot: File = File(file.parentFile, "archive"),
) {
  private val json = Json { ignoreUnknownKeys = true }
  private val lock = Any()

  fun append(
    type: SpineRecordType,
    payload: JsonObject,
    provenance: JsonObject = JsonObject(emptyMap()),
    sessionId: String? = null,
    traceId: String? = null,
    parentEventId: String? = null,
    occurredAtMs: Long = System.currentTimeMillis(),
  ): SpineRecord = synchronized(lock) {
    file.parentFile?.mkdirs()
    val previousHash = readCurrentRecords().records.lastOrNull()?.eventHash
    val body =
      buildJsonObject {
        put("schema_version", SCHEMA)
        put("event_id", UUID.randomUUID().toString())
        put("record_type", type.wireValue)
        put("occurred_at_ms", occurredAtMs)
        sessionId?.let { put("session_id", it) }
        traceId?.let { put("trace_id", it) }
        parentEventId?.let { put("parent_event_id", it) }
        put("payload", payload)
        put("provenance", provenance)
        previousHash?.let { put("previous_hash", it) }
      }
    val hash = TraceIntegrity.sha256(body.toString())
    val event = JsonObject(body + ("event_hash" to JsonPrimitive(hash)))
    file.appendText(event.toString() + "\n", Charsets.UTF_8)
    requireNotNull(parseRecord(event.toString(), expectedPreviousHash = previousHash))
  }

  fun read(
    type: SpineRecordType? = null,
    sessionId: String? = null,
  ): SpineReadResult = synchronized(lock) {
    val current = readCurrentRecords()
    val legacy = readLegacyRecords()
    val records =
      (current.records + legacy.records)
        .filter { type == null || it.type == type }
        .filter { sessionId == null || it.sessionId == sessionId }
        .sortedByDescending(SpineRecord::occurredAtMs)
    SpineReadResult(
      records = records,
      malformedLines = current.malformedLines + legacy.malformedLines,
      legacyRecords = legacy.records.size,
      integrityValid = current.integrityValid && legacy.integrityValid,
    )
  }

  fun archive(): File? = synchronized(lock) {
    if (!file.isFile || file.length() == 0L) return null
    archiveRoot.mkdirs()
    val target = File(archiveRoot, "runtime-spine-${System.currentTimeMillis()}.jsonl")
    try {
      Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (_: Exception) {
      Files.move(file.toPath(), target.toPath())
    }
    target
  }

  fun exportBytes(): ByteArray = synchronized(lock) {
    if (file.isFile) file.readBytes() else ByteArray(0)
  }

  private fun readCurrentRecords(): SpineReadResult {
    if (!file.isFile) return SpineReadResult(emptyList(), 0, 0, true)
    var malformed = 0
    var previousHash: String? = null
    val records =
      file.useLines { lines ->
        lines.mapNotNull { raw ->
          val record = parseRecord(raw, previousHash)
          if (record == null) {
            malformed += 1
          } else {
            previousHash = record.eventHash
          }
          record
        }.toList()
      }
    return SpineReadResult(records, malformed, 0, records.all(SpineRecord::integrityValid))
  }

  private fun parseRecord(raw: String, expectedPreviousHash: String?): SpineRecord? =
    runCatching {
        val obj = json.parseToJsonElement(raw) as JsonObject
        if (obj.string("schema_version") != SCHEMA) return null
        val hash = requireNotNull(obj.string("event_hash"))
        val withoutHash = JsonObject(obj - "event_hash")
        val validHash = TraceIntegrity.sha256(withoutHash.toString()) == hash
        val previous = obj.string("previous_hash")
        SpineRecord(
          eventId = requireNotNull(obj.string("event_id")),
          type = requireNotNull(SpineRecordType.fromWire(obj.string("record_type"))),
          occurredAtMs = obj["occurred_at_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
          sessionId = obj.string("session_id"),
          traceId = obj.string("trace_id"),
          parentEventId = obj.string("parent_event_id"),
          payload = obj["payload"] as? JsonObject ?: JsonObject(emptyMap()),
          provenance = obj["provenance"] as? JsonObject ?: JsonObject(emptyMap()),
          previousHash = previous,
          eventHash = hash,
          integrityValid = validHash && previous == expectedPreviousHash,
          raw = raw,
        )
      }
      .getOrNull()

  private fun readLegacyRecords(): SpineReadResult {
    val legacy = legacyTraceFile ?: return SpineReadResult(emptyList(), 0, 0, true)
    if (!legacy.isFile) return SpineReadResult(emptyList(), 0, 0, true)
    var malformed = 0
    val records =
      legacy.useLines { lines ->
        lines.mapNotNull { raw ->
          runCatching {
              val obj = json.parseToJsonElement(raw) as JsonObject
              val eventType = obj.string("event_type")
              val type =
                when (eventType) {
                  "inference_failed", "inference_cancelled" -> SpineRecordType.FAILURE_TRACE
                  "review" -> SpineRecordType.CORRECTION_TRACE
                  "model_snapshot_registered" -> SpineRecordType.PROVENANCE
                  else -> SpineRecordType.TRACE
                }
              SpineRecord(
                eventId = obj.string("event_id") ?: UUID.nameUUIDFromBytes(raw.toByteArray()).toString(),
                type = type,
                occurredAtMs = obj["created_at_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                sessionId = obj.string("session_id"),
                traceId = obj.string("trace_id"),
                parentEventId = null,
                payload = obj,
                provenance = buildJsonObject { put("source_schema", obj.string("schema_version") ?: "legacy") },
                previousHash = obj.string("previous_event_hash"),
                eventHash = obj.string("event_hash") ?: TraceIntegrity.sha256(raw),
                integrityValid = true,
                raw = raw,
              )
            }
            .onFailure { malformed += 1 }
            .getOrNull()
        }.toList()
      }
    return SpineReadResult(records, malformed, records.size, true)
  }

  private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

  companion object {
    const val SCHEMA = "runtime-spine.v1"
  }
}
