package com.terminus.edge.light.context

import kotlin.math.ceil
import kotlin.math.roundToInt

enum class ContextMode(val wireValue: String, val label: String) {
  MANUAL("manual", "Manual"),
  ASSISTED("assisted", "Assisted"),
  AUTOMATIC("automatic", "Auto");

  companion object {
    fun fromWireValue(value: String?): ContextMode =
      entries.firstOrNull { it.wireValue == value } ?: ASSISTED
  }
}

enum class RetentionPolicy(val wireValue: String, val label: String) {
  PINNED("pinned", "Pinned"),
  SAFE_RETENTION("safe_retention", "Retain"),
  COMPRESSIBLE("compressible", "Compress"),
  TEMPORARY("temporary", "Temporary"),
  EXCLUDED("excluded", "Excluded");
}

data class ContextSettings(
  val mode: ContextMode = ContextMode.ASSISTED,
  val compressionThresholdPercent: Int = 70,
  val reservedOutputTokens: Int = 1024,
) {
  fun normalized(totalTokens: Int): ContextSettings =
    (totalTokens / 2).coerceAtLeast(1).let { maximumReserve ->
      copy(
        compressionThresholdPercent = compressionThresholdPercent.coerceIn(50, 90),
        reservedOutputTokens =
          reservedOutputTokens.coerceIn(
            minimumValue = 128.coerceAtMost(maximumReserve),
            maximumValue = maximumReserve,
          ),
      )
    }
}

data class ManagedContextMessage(
  val id: String,
  val label: String,
  val content: String,
  val policy: RetentionPolicy,
)

data class FixedContextEntry(
  val id: String,
  val label: String,
  val content: String,
  val policy: RetentionPolicy = RetentionPolicy.PINNED,
)

data class ContextEntryView(
  val id: String,
  val label: String,
  val preview: String,
  val characters: Int,
  val estimatedTokens: Int,
  val policy: RetentionPolicy,
  val editable: Boolean,
  val compressed: Boolean,
  val included: Boolean,
)

data class ContextUsage(
  val characters: Int,
  val estimatedTokens: Int,
  val totalTokens: Int,
  val reservedOutputTokens: Int,
) {
  val percentage: Int
    get() =
      if (totalTokens <= 0) 0
      else ((estimatedTokens.toDouble() / totalTokens.toDouble()) * 100.0).roundToInt().coerceAtLeast(0)

  val inputLimitTokens: Int
    get() = (totalTokens - reservedOutputTokens).coerceAtLeast(1)

  val exceedsInputLimit: Boolean
    get() = estimatedTokens > inputLimitTokens
}

data class ContextSnapshot(
  val usage: ContextUsage,
  val settings: ContextSettings,
  val entries: List<ContextEntryView>,
  val effectivePrompt: String,
  val includedEntryIds: List<String>,
  val excludedEntryIds: List<String>,
  val compressedEntryIds: List<String>,
  val retainedEntryIds: List<String>,
  val compressionOperations: List<String> = emptyList(),
  val newlyCompressedIds: Set<String> = emptySet(),
) {
  val isCritical: Boolean
    get() = usage.percentage >= 90

  val shouldRecommendCompression: Boolean
    get() = usage.percentage >= settings.compressionThresholdPercent
}

data class ContextBuildInput(
  val systemPrompt: String,
  val totalTokens: Int,
  val settings: ContextSettings,
  val fixedEntries: List<FixedContextEntry>,
  val messages: List<ManagedContextMessage>,
  val compressedMessageIds: Set<String>,
  val recordedCompressionOperations: List<String> = emptyList(),
  val draftPrompt: String,
  val promptBuilder: (history: String?, draftPrompt: String) -> String,
)

object ContextWindowManager {
  fun snapshot(input: ContextBuildInput): ContextSnapshot =
    buildSnapshot(
      input,
      input.compressedMessageIds,
      input.recordedCompressionOperations,
      emptySet(),
    )

  fun compress(input: ContextBuildInput, forceOne: Boolean = false): ContextSnapshot {
    val normalizedSettings = input.settings.normalized(input.totalTokens)
    var compressedIds = input.compressedMessageIds
    var snapshot =
      buildSnapshot(
        input,
        compressedIds,
        input.recordedCompressionOperations,
        emptySet(),
      )
    val operations = input.recordedCompressionOperations.toMutableList()
    val newlyCompressed = linkedSetOf<String>()
    val protectedRecentIds =
      input.messages
        .filter { it.policy != RetentionPolicy.EXCLUDED }
        .takeLast(RECENT_MESSAGE_RESERVE)
        .map(ManagedContextMessage::id)
        .toSet()
    val candidates =
      input.messages
        .filter { it.id !in compressedIds }
        .filter {
          it.policy == RetentionPolicy.COMPRESSIBLE || it.policy == RetentionPolicy.TEMPORARY
        }
        .sortedWith(
          compareBy<ManagedContextMessage> { it.id in protectedRecentIds }
            .thenBy { it.policy != RetentionPolicy.TEMPORARY }
        )
        .toMutableList()

    while (
      candidates.isNotEmpty() &&
        (snapshot.usage.percentage >= normalizedSettings.compressionThresholdPercent ||
          snapshot.usage.exceedsInputLimit ||
          (forceOne && newlyCompressed.isEmpty()))
    ) {
      val candidate = candidates.removeAt(0)
      compressedIds = compressedIds + candidate.id
      newlyCompressed += candidate.id
      operations +=
        if (candidate.policy == RetentionPolicy.TEMPORARY) {
          "Removed temporary entry ${candidate.id}."
        } else {
          "Density-compressed entry ${candidate.id}."
        }
      snapshot = buildSnapshot(input, compressedIds, operations, newlyCompressed)
    }
    return snapshot
  }

  private fun buildSnapshot(
    input: ContextBuildInput,
    compressedIds: Set<String>,
    operations: List<String>,
    newlyCompressed: Set<String>,
  ): ContextSnapshot {
    val normalizedSettings = input.settings.normalized(input.totalTokens)
    val activeMessages =
      input.messages.filter {
        it.id !in compressedIds && it.policy != RetentionPolicy.EXCLUDED
      }
    val compressedMessages =
      input.messages.filter {
        it.id in compressedIds && it.policy != RetentionPolicy.EXCLUDED
      }
    val history = buildHistory(activeMessages, compressedMessages)
    val effectivePrompt = input.promptBuilder(history.ifBlank { null }, input.draftPrompt)
    val characters = input.systemPrompt.length + effectivePrompt.length
    val usage =
      ContextUsage(
        characters = characters,
        estimatedTokens = estimateTokens(characters),
        totalTokens = input.totalTokens,
        reservedOutputTokens = normalizedSettings.reservedOutputTokens,
      )
    val fixedViews =
      input.fixedEntries.map { entry ->
        entry.toView(editable = false, compressed = false, included = true)
      }
    val messageViews =
      input.messages.map { message ->
        ContextEntryView(
          id = message.id,
          label = message.label,
          preview = preview(message.content),
          characters = message.content.length,
          estimatedTokens = estimateTokens(message.content.length),
          policy = message.policy,
          editable = true,
          compressed = message.id in compressedIds,
          included = message.policy != RetentionPolicy.EXCLUDED,
        )
      }
    val draftView =
      input.draftPrompt
        .takeIf(String::isNotBlank)
        ?.let {
          ContextEntryView(
            id = DRAFT_ID,
            label = "Current message",
            preview = preview(it),
            characters = it.length,
            estimatedTokens = estimateTokens(it.length),
            policy = RetentionPolicy.PINNED,
            editable = false,
            compressed = false,
            included = true,
          )
        }
    val includedIds =
      buildList {
        addAll(input.fixedEntries.map(FixedContextEntry::id))
        addAll(activeMessages.map(ManagedContextMessage::id))
        if (compressedMessages.any { it.policy != RetentionPolicy.TEMPORARY }) add(DENSITY_SUMMARY_ID)
        if (draftView != null) add(DRAFT_ID)
      }
    val excludedIds =
      input.messages
        .filter {
          it.policy == RetentionPolicy.EXCLUDED ||
            (it.id in compressedIds && it.policy == RetentionPolicy.TEMPORARY)
        }
        .map(ManagedContextMessage::id)
    val retainedIds =
      input.fixedEntries
        .filter { it.policy == RetentionPolicy.PINNED || it.policy == RetentionPolicy.SAFE_RETENTION }
        .map(FixedContextEntry::id) +
        input.messages
          .filter {
            it.policy == RetentionPolicy.PINNED || it.policy == RetentionPolicy.SAFE_RETENTION
          }
          .map(ManagedContextMessage::id)
    return ContextSnapshot(
      usage = usage,
      settings = normalizedSettings,
      entries = fixedViews + messageViews + listOfNotNull(draftView),
      effectivePrompt = effectivePrompt,
      includedEntryIds = includedIds,
      excludedEntryIds = excludedIds.distinct(),
      compressedEntryIds = compressedIds.sorted(),
      retainedEntryIds = retainedIds.distinct(),
      compressionOperations = operations,
      newlyCompressedIds = newlyCompressed,
    )
  }

  private fun buildHistory(
    activeMessages: List<ManagedContextMessage>,
    compressedMessages: List<ManagedContextMessage>,
  ): String =
    buildString {
      val summaryEntries =
        compressedMessages.filter { it.policy != RetentionPolicy.TEMPORARY }
      if (summaryEntries.isNotEmpty()) {
        appendLine("<context_density_summary>")
        appendLine(
          "Locally generated extractive summary. Original entries remain recoverable on this device."
        )
        summaryEntries.forEach { message ->
          appendLine("- ${message.label}: ${densityCompress(message.content, message.policy)}")
        }
        appendLine("</context_density_summary>")
      }
      if (activeMessages.isNotEmpty()) {
        if (isNotEmpty()) appendLine()
        appendLine("<conversation_context>")
        appendLine("Earlier local conversation entries follow in chronological order.")
        activeMessages.forEach { message ->
          appendLine("[${message.label}; retention=${message.policy.wireValue}]")
          appendLine(message.content)
        }
        appendLine("</conversation_context>")
      }
    }.trim()

  private fun densityCompress(content: String, policy: RetentionPolicy): String {
    val normalized = content.replace(Regex("\\s+"), " ").trim()
    if (normalized.isEmpty()) return "(empty)"
    if (policy == RetentionPolicy.SAFE_RETENTION) return normalized.take(SAFE_RETENTION_LIMIT)

    val sentences = normalized.split(Regex("(?<=[.!?])\\s+"))
    val signal =
      sentences.filter { sentence ->
        val lower = sentence.lowercase()
        SIGNAL_WORDS.any(lower::contains) ||
          HASH_OR_ID.containsMatchIn(sentence) ||
          PATH.containsMatchIn(sentence)
      }
    return (listOf(sentences.first()) + signal)
      .distinct()
      .joinToString(" ")
      .take(COMPRESSED_ENTRY_LIMIT)
  }

  private fun FixedContextEntry.toView(
    editable: Boolean,
    compressed: Boolean,
    included: Boolean,
  ): ContextEntryView =
    ContextEntryView(
      id = id,
      label = label,
      preview = preview(content),
      characters = content.length,
      estimatedTokens = estimateTokens(content.length),
      policy = policy,
      editable = editable,
      compressed = compressed,
      included = included,
    )

  fun estimateTokens(characters: Int): Int =
    ceil(characters.coerceAtLeast(0) / APPROXIMATE_CHARACTERS_PER_TOKEN).toInt()

  private fun preview(content: String): String =
    content.replace(Regex("\\s+"), " ").trim().take(PREVIEW_LIMIT).ifEmpty { "(empty)" }

  private const val APPROXIMATE_CHARACTERS_PER_TOKEN = 4.0
  private const val PREVIEW_LIMIT = 100
  private const val COMPRESSED_ENTRY_LIMIT = 360
  private const val SAFE_RETENTION_LIMIT = 1200
  private const val RECENT_MESSAGE_RESERVE = 4
  private const val DENSITY_SUMMARY_ID = "density-summary"
  private const val DRAFT_ID = "draft"
  private val SIGNAL_WORDS =
    listOf(
      "must",
      "must not",
      "do not",
      "approved",
      "decision",
      "require",
      "constraint",
      "version",
      "retain",
      "blocked",
      "next",
    )
  private val HASH_OR_ID = Regex("\\b[a-f0-9]{8,64}\\b|\\b[A-Z][A-Z0-9_-]{3,}\\b")
  private val PATH = Regex("(/[^\\s]+)+")
}
