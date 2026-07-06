package com.terminus.edge.light.trace

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class ReviewDecision(val wireValue: String) {
  KEEP("keep"),
  REJECT("reject"),
  EDITED("edited");

  companion object {
    fun fromWireValue(value: String?): ReviewDecision? = entries.firstOrNull { it.wireValue == value }
  }
}

data class ReviewRubric(
  val correctness: Int? = null,
  val usefulness: Int? = null,
  val groundedness: Int? = null,
  val safety: Int? = null,
) {
  init {
    listOf(correctness, usefulness, groundedness, safety)
      .filterNotNull()
      .forEach { require(it in 1..5) { "Review scores must be between 1 and 5." } }
  }

  val isEmpty: Boolean
    get() = correctness == null && usefulness == null && groundedness == null && safety == null
}

data class InferenceStartedTrace(
  val traceId: String = UUID.randomUUID().toString(),
  val sessionId: String,
  val createdAtMs: Long,
  val turnIndex: Int,
  val parentTraceId: String?,
  val historyState: String,
  val userPrompt: String,
  val effectivePrompt: String,
  val systemPrompt: String,
  val historyArtifact: TraceArtifactRef?,
  val model: ModelSnapshot,
  val maxTokens: Int,
  val topK: Int,
  val topP: Double,
  val temperature: Double,
  val imageInputEnabled: Boolean,
  val appVersion: String,
  val runtimeName: String,
  val runtimeVersion: String,
  val backend: String,
  val skillArtifacts: List<TraceArtifactRef> = emptyList(),
  val imageArtifacts: List<TraceArtifactRef> = emptyList(),
  val contextManagement: ContextManagementTrace? = null,
)

data class ContextManagementTrace(
  val totalTokens: Int,
  val estimatedInputTokens: Int,
  val inputCharacters: Int,
  val reservedOutputTokens: Int,
  val estimateMethod: String,
  val mode: String,
  val compressionThresholdPercent: Int,
  val includedEntryIds: List<String>,
  val excludedEntryIds: List<String>,
  val compressedEntryIds: List<String>,
  val retainedEntryIds: List<String>,
  val compressionOperations: List<String>,
)

data class InferenceCompletedTrace(
  val traceId: String,
  val sessionId: String,
  val createdAtMs: Long,
  val response: String,
  val latencyMs: Long,
  val timeToFirstChunkMs: Long?,
  val chunkCount: Int,
  val finishReason: String = "completed",
)

data class InferenceOutcomeTrace(
  val traceId: String,
  val sessionId: String,
  val createdAtMs: Long,
  val latencyMs: Long,
  val timeToFirstChunkMs: Long?,
  val chunkCount: Int,
  val partialResponse: String,
  val category: String,
  val message: String?,
)

data class ReviewTrace(
  val traceId: String,
  val createdAtMs: Long,
  val decision: ReviewDecision,
  val correctedResponse: String? = null,
  val note: String? = null,
  val tags: List<String> = emptyList(),
  val rubric: ReviewRubric = ReviewRubric(),
  val originalResponseSha256: String? = null,
)

data class TraceStats(
  val eventCount: Int,
  val inferenceAttempts: Int,
  val completed: Int,
  val failed: Int,
  val cancelled: Int,
  val reviewed: Int,
  val unreviewed: Int,
  val malformedLines: Int,
  val duplicateTraceIds: Int,
  val orphanReviews: Int,
  val legacyEvents: Int,
  val integrityStatus: String,
  val chainHeadSha256: String?,
)

data class ReplayExportResult(
  val eventCount: Int,
  val traceCount: Int,
  val artifactCount: Int,
  val modelCount: Int,
)

class TraceLedger(
  private val ledgerFile: File,
  private val artifactStore: TraceArtifactStore? = null,
) {
  private val json = Json { ignoreUnknownKeys = true }
  private val lock = Any()

  fun appendConsent(
    enabled: Boolean,
    sessionId: String,
    createdAtMs: Long,
    appVersion: String,
  ): String =
    appendEvent(
        buildJsonObject {
          put("schema_version", TRACE_SCHEMA)
          put("event_type", "consent_changed")
          put("event_id", UUID.randomUUID().toString())
          put("session_id", sessionId)
          put("created_at_ms", createdAtMs)
          put("scope", "local_trace_capture")
          put("enabled", enabled)
          put("consent_version", 1)
          put("app_version", appVersion)
        }
      )
      .string("event_id")
      .orEmpty()

  fun appendModelSnapshot(
    snapshot: ModelSnapshot,
    sessionId: String,
    createdAtMs: Long,
    appVersion: String,
  ): String {
    val artifact =
      requireNotNull(snapshot.artifact) { "Replay export requires a model binary snapshot." }
    return appendEvent(
        buildJsonObject {
          put("schema_version", TRACE_SCHEMA)
          put("event_type", "model_snapshot_registered")
          put("event_id", UUID.randomUUID().toString())
          put("session_id", sessionId)
          put("created_at_ms", createdAtMs)
          put(
            "model",
            buildJsonObject {
              put("name", snapshot.displayName)
              put("sha256", snapshot.sha256)
              put("size_bytes", snapshot.sizeBytes)
              put("imported_at_ms", snapshot.importedAtMs)
              put("snapshot", artifact.toJson())
            },
          )
          put("reason", "replay_export")
          put("app_version", appVersion)
        }
      )
      .string("event_id")
      .orEmpty()
  }

  fun hasModelSnapshot(sha256: String): Boolean = synchronized(lock) {
    readEvents()
      .mapNotNull(ParsedLine::event)
      .flatMap(::collectArtifactRefs)
      .any { it.kind == "models" && it.sha256 == sha256 }
  }

  fun appendInferenceStarted(trace: InferenceStartedTrace): String =
    appendEvent(
        buildJsonObject {
          put("schema_version", TRACE_SCHEMA)
          put("event_type", "inference_started")
          put("event_id", UUID.randomUUID().toString())
          put("trace_id", trace.traceId)
          put("session_id", trace.sessionId)
          put("created_at_ms", trace.createdAtMs)
          put("consent", "operator_opt_in_local")
          put(
            "turn",
            buildJsonObject {
              put("index", trace.turnIndex)
              put("history_state", trace.historyState)
              trace.parentTraceId?.let { put("parent_trace_id", it) }
              trace.historyArtifact?.let { put("history_artifact", it.toJson()) }
            },
          )
          put(
            "input",
            buildJsonObject {
              put("user_prompt", trace.userPrompt)
              put("user_prompt_sha256", TraceIntegrity.sha256(trace.userPrompt))
              put("effective_prompt", trace.effectivePrompt)
              put("effective_prompt_sha256", TraceIntegrity.sha256(trace.effectivePrompt))
              put("system_prompt", trace.systemPrompt)
              put("system_prompt_sha256", TraceIntegrity.sha256(trace.systemPrompt))
            },
          )
          put(
            "model",
            buildJsonObject {
              put("name", trace.model.displayName)
              put("sha256", trace.model.sha256)
              put("size_bytes", trace.model.sizeBytes)
              put("imported_at_ms", trace.model.importedAtMs)
              trace.model.artifact?.let { put("snapshot", it.toJson()) }
            },
          )
          put(
            "runtime",
            buildJsonObject {
              put("name", trace.runtimeName)
              put("version", trace.runtimeVersion)
              put("backend", trace.backend)
            },
          )
          put(
            "generation",
            buildJsonObject {
              put("max_tokens", trace.maxTokens)
              put("top_k", trace.topK)
              put("top_p", trace.topP)
              put("temperature", trace.temperature)
              put("image_input_enabled", trace.imageInputEnabled)
            },
          )
          put(
            "context",
            buildJsonObject {
              put(
                "skills",
                buildJsonArray { trace.skillArtifacts.forEach { add(it.toJson()) } },
              )
              put(
                "images",
                buildJsonArray { trace.imageArtifacts.forEach { add(it.toJson()) } },
              )
              trace.contextManagement?.let { management ->
                put(
                  "management",
                  buildJsonObject {
                    put("total_tokens", management.totalTokens)
                    put("estimated_input_tokens", management.estimatedInputTokens)
                    put("input_characters", management.inputCharacters)
                    put("reserved_output_tokens", management.reservedOutputTokens)
                    put("estimate_method", management.estimateMethod)
                    put("mode", management.mode)
                    put(
                      "compression_threshold_percent",
                      management.compressionThresholdPercent,
                    )
                    put(
                      "included_entry_ids",
                      buildJsonArray {
                        management.includedEntryIds.forEach { add(JsonPrimitive(it)) }
                      },
                    )
                    put(
                      "excluded_entry_ids",
                      buildJsonArray {
                        management.excludedEntryIds.forEach { add(JsonPrimitive(it)) }
                      },
                    )
                    put(
                      "compressed_entry_ids",
                      buildJsonArray {
                        management.compressedEntryIds.forEach { add(JsonPrimitive(it)) }
                      },
                    )
                    put(
                      "retained_entry_ids",
                      buildJsonArray {
                        management.retainedEntryIds.forEach { add(JsonPrimitive(it)) }
                      },
                    )
                    put(
                      "compression_operations",
                      buildJsonArray {
                        management.compressionOperations.forEach { add(JsonPrimitive(it)) }
                      },
                    )
                  },
                )
              }
            },
          )
          put("app_version", trace.appVersion)
        }
      )
      .string("event_id")
      .orEmpty()

  fun appendInferenceCompleted(trace: InferenceCompletedTrace): String =
    appendEvent(
        buildJsonObject {
          put("schema_version", TRACE_SCHEMA)
          put("event_type", "inference_completed")
          put("event_id", UUID.randomUUID().toString())
          put("trace_id", trace.traceId)
          put("session_id", trace.sessionId)
          put("created_at_ms", trace.createdAtMs)
          put("response", trace.response)
          put("response_sha256", TraceIntegrity.sha256(trace.response))
          put(
            "metrics",
            metricsJson(
              latencyMs = trace.latencyMs,
              timeToFirstChunkMs = trace.timeToFirstChunkMs,
              chunkCount = trace.chunkCount,
              outputChars = trace.response.length,
              finishReason = trace.finishReason,
            ),
          )
        }
      )
      .string("event_id")
      .orEmpty()

  fun appendInferenceFailed(trace: InferenceOutcomeTrace): String =
    appendOutcome("inference_failed", trace)

  fun appendInferenceCancelled(trace: InferenceOutcomeTrace): String =
    appendOutcome("inference_cancelled", trace)

  fun appendReview(review: ReviewTrace): String {
    require(review.decision != ReviewDecision.EDITED || !review.correctedResponse.isNullOrBlank()) {
      "Edited reviews require a corrected response."
    }
    val normalizedTags =
      review.tags.map(String::trim).filter(String::isNotEmpty).distinct().take(MAX_REVIEW_TAGS)
    val supersedesEventId = synchronized(lock) { latestReviewEventId(review.traceId) }
    return appendEvent(
        buildJsonObject {
          put("schema_version", TRACE_SCHEMA)
          put("event_type", "review")
          put("event_id", UUID.randomUUID().toString())
          put("trace_id", review.traceId)
          put("created_at_ms", review.createdAtMs)
          put("decision", review.decision.wireValue)
          put("reviewer", "operator_local")
          supersedesEventId?.let { put("supersedes_event_id", it) }
          review.originalResponseSha256?.let { put("original_response_sha256", it) }
          review.correctedResponse?.let {
            put("corrected_response", it)
            put("corrected_response_sha256", TraceIntegrity.sha256(it))
          }
          review.note?.trim()?.takeIf(String::isNotEmpty)?.let { put("note", it) }
          if (normalizedTags.isNotEmpty()) {
            put("tags", buildJsonArray { normalizedTags.forEach { add(JsonPrimitive(it)) } })
          }
          if (!review.rubric.isEmpty) put("rubric", review.rubric.toJson())
        }
      )
      .string("event_id")
      .orEmpty()
  }

  fun exportRaw(output: OutputStream) {
    synchronized(lock) {
      output.bufferedWriter().use { writer ->
        if (ledgerFile.isFile) ledgerFile.forEachLine { line -> writer.appendLine(line) }
      }
    }
  }

  fun exportCurated(output: OutputStream): Int = synchronized(lock) {
    val parsed = readEvents()
    val stats = statsLocked(parsed)
    require(stats.integrityStatus != "failed") {
      "Trace integrity verification failed. Export raw data for inspection."
    }
    val events = parsed.mapNotNull(ParsedLine::event)
    val starts = linkedMapOf<String, JsonObject>()
    val completions = linkedMapOf<String, JsonObject>()
    val legacyInferences = linkedMapOf<String, JsonObject>()
    val reviews = mutableMapOf<String, JsonObject>()
    val traceOrder = mutableListOf<String>()

    events.forEach { event ->
      val traceId = event.string("trace_id")
      when (event.string("event_type")) {
        "inference_started" ->
          if (traceId != null && starts.putIfAbsent(traceId, event) == null) traceOrder += traceId
        "inference_completed" ->
          if (traceId != null) completions.putIfAbsent(traceId, event)
        "inference" ->
          if (traceId != null && legacyInferences.putIfAbsent(traceId, event) == null) {
            traceOrder += traceId
          }
        "review" -> if (traceId != null) reviews[traceId] = event
      }
    }

    var exported = 0
    output.bufferedWriter().use { writer ->
      traceOrder.distinct().forEach { traceId ->
        val record =
          when {
            starts[traceId] != null && completions[traceId] != null ->
              curatedV2Record(traceId, starts.getValue(traceId), completions.getValue(traceId), reviews[traceId])
            legacyInferences[traceId] != null ->
              curatedV1Record(traceId, legacyInferences.getValue(traceId), reviews[traceId])
            else -> null
          } ?: return@forEach
        writer.appendLine(record.toString())
        exported += 1
      }
    }
    exported
  }

  fun exportReplay(
    output: OutputStream,
    extraFiles: Map<String, ByteArray> = emptyMap(),
    extraArtifacts: Map<String, Pair<File, String?>> = emptyMap(),
  ): ReplayExportResult = synchronized(lock) {
    val store = requireNotNull(artifactStore) { "Replay export requires a trace artifact store." }
    val parsed = readEvents()
    val events = parsed.mapNotNull(ParsedLine::event)
    val stats = statsLocked(parsed)
    require(stats.integrityStatus != "failed") {
      "Trace integrity verification failed. Export raw data for inspection."
    }
    val references =
      events
        .flatMap(::collectArtifactRefs)
        .associateBy(TraceArtifactRef::storagePath)
        .values
        .sortedBy(TraceArtifactRef::storagePath)
    val requiredModelHashes =
      events
        .filter {
          it.string("event_type") == "inference_started" ||
            it.string("event_type") == "inference"
        }
        .mapNotNull { it.objectValue("model").string("sha256") }
        .filter(String::isNotBlank)
        .toSet()
    val includedModelHashes =
      references.filter { it.kind == "models" }.map(TraceArtifactRef::sha256).toSet()
    val missingModelHashes = requiredModelHashes - includedModelHashes
    require(missingModelHashes.isEmpty()) {
      "Replay is missing model snapshot(s): ${missingModelHashes.joinToString { it.take(12) }}."
    }
    val traceCount = stats.inferenceAttempts
    val modelCount = references.count { it.kind == "models" }
    val files = mutableListOf<JsonObject>()

    ZipOutputStream(output.buffered()).use { zip ->
      zip.setLevel(6)
      files += writeZipFile(zip, "events.jsonl", ledgerFile, expectedSha256 = null)
      references.forEach { reference ->
        val file = store.resolve(reference)
        zip.setLevel(if (reference.kind == "models") 0 else 6)
        files +=
          writeZipFile(
            zip = zip,
            path = "artifacts/${reference.storagePath}",
            file = file,
            expectedSha256 = reference.sha256,
          )
      }
      extraFiles.toSortedMap().forEach { (path, bytes) ->
        files += writeZipBytes(zip, path, bytes)
      }
      extraArtifacts.toSortedMap().forEach { (path, artifact) ->
        files += writeZipFile(zip, path, artifact.first, artifact.second)
      }
      zip.setLevel(6)
      val manifest =
        buildJsonObject {
          put("schema_version", REPLAY_SCHEMA)
          put("created_at_ms", System.currentTimeMillis())
          put("event_count", stats.eventCount)
          put("trace_count", traceCount)
          put(
            "integrity",
            buildJsonObject {
              put("status", stats.integrityStatus)
              if (stats.chainHeadSha256 == null) put("chain_head_sha256", JsonNull)
              else put("chain_head_sha256", stats.chainHeadSha256)
              put("legacy_events", stats.legacyEvents)
              put("malformed_lines", stats.malformedLines)
              put("duplicate_trace_ids", stats.duplicateTraceIds)
              put("orphan_reviews", stats.orphanReviews)
            },
          )
          put(
            "replay_scope",
            buildJsonObject {
              put("inputs_and_artifacts", "exact_snapshot")
              put("model_binary_included", modelCount > 0)
              put(
                "required_model_sha256",
                buildJsonArray {
                  requiredModelHashes.sorted().forEach { add(JsonPrimitive(it)) }
                },
              )
              put(
                "included_model_sha256",
                buildJsonArray {
                  includedModelHashes.sorted().forEach { add(JsonPrimitive(it)) }
                },
              )
              put("output_determinism", "not_guaranteed")
              put(
                "note",
                "Sampling, runtime, and hardware differences can change generated output.",
              )
            },
          )
          put("files", buildJsonArray { files.forEach { add(it) } })
        }
      writeZipBytes(zip, "manifest.json", manifest.toString().toByteArray(Charsets.UTF_8))
    }
    ReplayExportResult(
      eventCount = stats.eventCount,
      traceCount = traceCount,
      artifactCount = references.size,
      modelCount = modelCount,
    )
  }

  fun stats(): TraceStats = synchronized(lock) { statsLocked(readEvents()) }

  fun deleteAll(): Boolean = synchronized(lock) {
    val ledgerDeleted = !ledgerFile.exists() || ledgerFile.delete()
    val artifactsDeleted = artifactStore?.deleteAll() ?: true
    ledgerDeleted && artifactsDeleted
  }

  fun eventCount(): Int = stats().eventCount

  private fun appendOutcome(eventType: String, trace: InferenceOutcomeTrace): String =
    appendEvent(
        buildJsonObject {
          put("schema_version", TRACE_SCHEMA)
          put("event_type", eventType)
          put("event_id", UUID.randomUUID().toString())
          put("trace_id", trace.traceId)
          put("session_id", trace.sessionId)
          put("created_at_ms", trace.createdAtMs)
          if (trace.partialResponse.isNotEmpty()) {
            put("partial_response", trace.partialResponse)
            put("partial_response_sha256", TraceIntegrity.sha256(trace.partialResponse))
          }
          put(
            "metrics",
            metricsJson(
              latencyMs = trace.latencyMs,
              timeToFirstChunkMs = trace.timeToFirstChunkMs,
              chunkCount = trace.chunkCount,
              outputChars = trace.partialResponse.length,
              finishReason = if (eventType == "inference_cancelled") "cancelled" else "error",
            ),
          )
          put(
            "error",
            buildJsonObject {
              put("category", trace.category)
              trace.message?.take(MAX_ERROR_MESSAGE)?.let { put("message", it) }
            },
          )
        }
      )
      .string("event_id")
      .orEmpty()

  private fun appendEvent(event: JsonObject): JsonObject = synchronized(lock) {
    val previousHash = lastChainHash()
    val chained =
      buildJsonObject {
        event.forEach { (key, value) -> put(key, value) }
        previousHash?.let { put("previous_event_hash", it) }
      }
    val eventHash = TraceIntegrity.sha256(canonicalJson(chained))
    val finalized =
      buildJsonObject {
        chained.forEach { (key, value) -> put(key, value) }
        put("event_hash", eventHash)
      }
    ledgerFile.parentFile?.mkdirs()
    val bytes = "${finalized}\n".toByteArray(Charsets.UTF_8)
    FileOutputStream(ledgerFile, true).use { output ->
      if (ledgerFile.length() > 0L && lastByte(ledgerFile) != '\n'.code) {
        output.write('\n'.code)
      }
      output.write(bytes)
      output.fd.sync()
    }
    finalized
  }

  private fun latestReviewEventId(traceId: String): String? {
    if (!ledgerFile.isFile) return null
    var latest: String? = null
    ledgerFile.forEachLine { line ->
      val event = parseEvent(line) ?: return@forEachLine
      if (event.string("event_type") == "review" && event.string("trace_id") == traceId) {
        latest = event.string("event_id")
      }
    }
    return latest
  }

  private fun lastChainHash(): String? {
    if (!ledgerFile.isFile) return null
    var latest: String? = null
    ledgerFile.forEachLine { line ->
      val event = parseEvent(line) ?: return@forEachLine
      if (event.string("schema_version") == TRACE_SCHEMA) latest = event.string("event_hash")
    }
    return latest
  }

  private fun readEvents(): List<ParsedLine> {
    if (!ledgerFile.isFile) return emptyList()
    return ledgerFile.readLines(Charsets.UTF_8).map { line -> ParsedLine(line, parseEvent(line)) }
  }

  private fun statsLocked(lines: List<ParsedLine>): TraceStats {
    val events = lines.mapNotNull(ParsedLine::event)
    val malformed = lines.count { it.event == null && it.raw.isNotBlank() }
    val starts = mutableMapOf<String, Int>()
    val completions = mutableMapOf<String, Int>()
    val legacyInferences = mutableMapOf<String, Int>()
    val failedIds = mutableSetOf<String>()
    val cancelledIds = mutableSetOf<String>()
    val reviewedIds = mutableSetOf<String>()
    var legacyEvents = 0
    var chainFailed = false
    var expectedPreviousHash: String? = null
    var chainHead: String? = null

    events.forEach { event ->
      val schema = event.string("schema_version")
      if (schema != TRACE_SCHEMA) {
        legacyEvents += 1
      } else {
        val actualHash = event.string("event_hash")
        val withoutHash = JsonObject(event.filterKeys { it != "event_hash" })
        val computedHash = TraceIntegrity.sha256(canonicalJson(withoutHash))
        val previousHash = event.string("previous_event_hash")
        if (actualHash == null || actualHash != computedHash || previousHash != expectedPreviousHash) {
          chainFailed = true
        }
        expectedPreviousHash = actualHash
        chainHead = actualHash
      }
      val traceId = event.string("trace_id")
      when (event.string("event_type")) {
        "inference_started" -> if (traceId != null) starts.increment(traceId)
        "inference_completed" -> if (traceId != null) completions.increment(traceId)
        "inference" -> if (traceId != null) legacyInferences.increment(traceId)
        "inference_failed" -> if (traceId != null) failedIds += traceId
        "inference_cancelled" -> if (traceId != null) cancelledIds += traceId
        "review" -> if (traceId != null) reviewedIds += traceId
      }
    }

    val completedIds = completions.keys + legacyInferences.keys
    val duplicateTraceIds =
      (starts.values + completions.values + legacyInferences.values).count { it > 1 }
    val integrityStatus =
      when {
        events.isEmpty() && malformed == 0 -> "empty"
        chainFailed -> "failed"
        malformed > 0 -> "verified_with_malformed_lines"
        legacyEvents > 0 -> "partial_legacy"
        else -> "verified"
      }
    return TraceStats(
      eventCount = events.size,
      inferenceAttempts = (starts.keys + legacyInferences.keys).size,
      completed = completedIds.size,
      failed = failedIds.size,
      cancelled = cancelledIds.size,
      reviewed = completedIds.intersect(reviewedIds).size,
      unreviewed = completedIds.subtract(reviewedIds).size,
      malformedLines = malformed,
      duplicateTraceIds = duplicateTraceIds,
      orphanReviews = reviewedIds.subtract(completedIds).size,
      legacyEvents = legacyEvents,
      integrityStatus = integrityStatus,
      chainHeadSha256 = chainHead,
    )
  }

  private fun curatedV2Record(
    traceId: String,
    started: JsonObject,
    completed: JsonObject,
    review: JsonObject?,
  ): JsonObject? {
    review ?: return null
    val decision = ReviewDecision.fromWireValue(review.string("decision")) ?: return null
    if (decision == ReviewDecision.REJECT) return null
    val input = started.objectValue("input")
    val response =
      if (decision == ReviewDecision.EDITED) {
        review.string("corrected_response")?.takeIf(String::isNotBlank) ?: return null
      } else {
        completed.string("response") ?: return null
      }
    val effectivePrompt = input.string("effective_prompt") ?: return null
    val userPrompt = input.string("user_prompt") ?: effectivePrompt
    val systemPrompt = input.string("system_prompt").orEmpty()
    return buildJsonObject {
      put("schema_version", TRAINING_SCHEMA)
      put("trace_id", traceId)
      put(
        "messages",
        buildJsonArray {
          if (systemPrompt.isNotBlank()) add(message("system", systemPrompt))
          add(message("user", effectivePrompt))
          add(message("assistant", response))
        },
      )
      put(
        "source_input",
        buildJsonObject {
          put("user_prompt", userPrompt)
          put("effective_prompt_sha256", input.string("effective_prompt_sha256").orEmpty())
        },
      )
      put("model", started.objectValue("model"))
      put("runtime", started.objectValue("runtime"))
      put("generation", started.objectValue("generation"))
      put("context", started.objectValue("context"))
      put("metrics", completed.objectValue("metrics"))
      put("review", curatedReview(review, decision))
      put(
        "provenance",
        buildJsonObject {
          put("capture", "operator_opt_in_local")
          put("source_trace_schema", TRACE_SCHEMA)
          put("source_start_event_id", started.string("event_id").orEmpty())
          put("source_completion_event_id", completed.string("event_id").orEmpty())
          put("source_start_event_hash", started.string("event_hash").orEmpty())
          put("source_completion_event_hash", completed.string("event_hash").orEmpty())
          put("rights_status", "unverified_for_training")
        },
      )
    }
  }

  private fun curatedV1Record(
    traceId: String,
    inference: JsonObject,
    review: JsonObject?,
  ): JsonObject? {
    review ?: return null
    val decision = ReviewDecision.fromWireValue(review.string("decision")) ?: return null
    if (decision == ReviewDecision.REJECT) return null
    val originalResponse = inference.string("response") ?: return null
    val response =
      if (decision == ReviewDecision.EDITED) {
        review.string("corrected_response")?.takeIf(String::isNotBlank) ?: return null
      } else {
        originalResponse
      }
    val prompt = inference.string("prompt") ?: return null
    val systemPrompt = inference.string("system_prompt").orEmpty()
    return buildJsonObject {
      put("schema_version", "edge-training.v1")
      put("trace_id", traceId)
      put(
        "messages",
        buildJsonArray {
          if (systemPrompt.isNotBlank()) add(message("system", systemPrompt))
          add(message("user", prompt))
          add(message("assistant", response))
        },
      )
      put("model", inference.objectValue("model"))
      put("generation", inference.objectValue("generation"))
      inference["seal_ids"]?.let { put("seal_ids", it) }
      inference["skill_ids"]?.let { put("skill_ids", it) }
      put("review", curatedReview(review, decision))
      put(
        "provenance",
        buildJsonObject {
          put("capture", "operator_opt_in_local")
          put("source_trace_schema", "edge-trace.v1")
          put("rights_status", "unverified_for_training")
        },
      )
    }
  }

  private fun curatedReview(review: JsonObject, decision: ReviewDecision): JsonObject =
    buildJsonObject {
      put("decision", decision.wireValue)
      put("operator_selected", true)
      review.string("event_id")?.let { put("event_id", it) }
      review.string("event_hash")?.let { put("event_hash", it) }
      review.string("note")?.let { put("note", it) }
      review["tags"]?.let { put("tags", it) }
      review["rubric"]?.let { put("rubric", it) }
    }

  private fun metricsJson(
    latencyMs: Long,
    timeToFirstChunkMs: Long?,
    chunkCount: Int,
    outputChars: Int,
    finishReason: String,
  ): JsonObject =
    buildJsonObject {
      put("latency_ms", latencyMs)
      if (timeToFirstChunkMs == null) put("time_to_first_chunk_ms", JsonNull)
      else put("time_to_first_chunk_ms", timeToFirstChunkMs)
      put("chunk_count", chunkCount)
      put("output_chars", outputChars)
      put("prompt_tokens", JsonNull)
      put("output_tokens", JsonNull)
      put("token_count_source", "runtime_unavailable")
      put("finish_reason", finishReason)
    }

  private fun ReviewRubric.toJson(): JsonObject =
    buildJsonObject {
      correctness?.let { put("correctness", it) }
      usefulness?.let { put("usefulness", it) }
      groundedness?.let { put("groundedness", it) }
      safety?.let { put("safety", it) }
      put("scale", "1_to_5")
    }

  private fun TraceArtifactRef.toJson(): JsonObject =
    buildJsonObject {
      put("kind", kind)
      logicalId?.let { put("logical_id", it) }
      put("sha256", sha256)
      put("size_bytes", sizeBytes)
      put("media_type", mediaType)
      put("storage_path", storagePath)
      fingerprint?.let { put("fingerprint", it) }
    }

  private fun collectArtifactRefs(element: JsonElement): List<TraceArtifactRef> {
    val result = mutableListOf<TraceArtifactRef>()
    fun visit(current: JsonElement) {
      when (current) {
        is JsonObject -> {
          val path = current.string("storage_path")
          val sha256 = current.string("sha256")
          val sizeBytes = current.long("size_bytes")
          val kind = current.string("kind")
          val mediaType = current.string("media_type")
          if (path != null && sha256 != null && sizeBytes != null && kind != null && mediaType != null) {
            result +=
              TraceArtifactRef(
                kind = kind,
                logicalId = current.string("logical_id"),
                sha256 = sha256,
                sizeBytes = sizeBytes,
                mediaType = mediaType,
                storagePath = path,
                fingerprint = current.string("fingerprint"),
              )
          } else {
            current.values.forEach(::visit)
          }
        }
        is JsonArray -> current.forEach(::visit)
        else -> Unit
      }
    }
    visit(element)
    return result
  }

  private fun writeZipFile(
    zip: ZipOutputStream,
    path: String,
    file: File,
    expectedSha256: String?,
  ): JsonObject {
    require(file.isFile) { "Missing replay file: $path" }
    val digest = MessageDigest.getInstance("SHA-256")
    var sizeBytes = 0L
    zip.putNextEntry(ZipEntry(path))
    file.inputStream().use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        zip.write(buffer, 0, count)
        digest.update(buffer, 0, count)
        sizeBytes += count
      }
    }
    zip.closeEntry()
    val sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    require(expectedSha256 == null || expectedSha256 == sha256) {
      "Replay artifact hash mismatch: $path"
    }
    return buildJsonObject {
      put("path", path)
      put("sha256", sha256)
      put("size_bytes", sizeBytes)
    }
  }

  private fun writeZipBytes(zip: ZipOutputStream, path: String, bytes: ByteArray): JsonObject {
    zip.putNextEntry(ZipEntry(path))
    zip.write(bytes)
    zip.closeEntry()
    return buildJsonObject {
      put("path", path)
      put("sha256", TraceIntegrity.sha256(bytes))
      put("size_bytes", bytes.size)
    }
  }

  private fun parseEvent(line: String): JsonObject? =
    runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()

  private fun lastByte(file: File): Int =
    RandomAccessFile(file, "r").use { input ->
      input.seek(input.length() - 1L)
      input.read()
    }

  private fun canonicalJson(element: JsonElement): String =
    when (element) {
      is JsonObject ->
        element.keys.sorted().joinToString(prefix = "{", postfix = "}") { key ->
          "${JsonPrimitive(key)}:${canonicalJson(element.getValue(key))}"
        }
      is JsonArray -> element.joinToString(prefix = "[", postfix = "]", transform = ::canonicalJson)
      else -> element.toString()
    }

  private fun JsonObject.string(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull

  private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull

  private fun JsonObject.objectValue(key: String): JsonObject =
    get(key) as? JsonObject ?: JsonObject(emptyMap())

  private fun MutableMap<String, Int>.increment(key: String) {
    this[key] = getOrDefault(key, 0) + 1
  }

  private fun message(role: String, content: String): JsonObject =
    buildJsonObject {
      put("role", role)
      put("content", content)
    }

  private data class ParsedLine(val raw: String, val event: JsonObject?)

  private companion object {
    const val TRACE_SCHEMA = "edge-trace.v2"
    const val TRAINING_SCHEMA = "edge-training.v2"
    const val REPLAY_SCHEMA = "edge-replay.v1"
    const val MAX_REVIEW_TAGS = 12
    const val MAX_ERROR_MESSAGE = 500
  }
}
