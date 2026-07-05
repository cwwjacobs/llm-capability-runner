package com.terminus.edge.light.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextWindowTest {
  @Test
  fun reportsExactCharactersAndEstimatedTokens() {
    val snapshot =
      ContextWindowManager.snapshot(
        input(
          systemPrompt = "1234",
          draftPrompt = "5678",
          totalTokens = 32,
          messages = emptyList(),
        )
      )

    assertEquals(8, snapshot.usage.characters)
    assertEquals(2, snapshot.usage.estimatedTokens)
    assertEquals(6, snapshot.usage.percentage)
  }

  @Test
  fun automaticCompressionProtectsPinnedAndSafeEntries() {
    val messages =
      listOf(
        message("pinned", RetentionPolicy.PINNED, "Pinned fact. ".repeat(30)),
        message("safe", RetentionPolicy.SAFE_RETENTION, "Approved constraint. ".repeat(30)),
        message("old-a", RetentionPolicy.COMPRESSIBLE, "Old discussion. ".repeat(40)),
        message("old-b", RetentionPolicy.COMPRESSIBLE, "Another old discussion. ".repeat(40)),
        message("recent-a", RetentionPolicy.COMPRESSIBLE, "Recent message. ".repeat(30)),
        message("recent-b", RetentionPolicy.COMPRESSIBLE, "Recent answer. ".repeat(30)),
      )
    val snapshot =
      ContextWindowManager.compress(
        input(
          totalTokens = 300,
          messages = messages,
          settings =
            ContextSettings(
              mode = ContextMode.AUTOMATIC,
              compressionThresholdPercent = 50,
              reservedOutputTokens = 64,
            ),
        )
      )

    assertFalse("pinned" in snapshot.newlyCompressedIds)
    assertFalse("safe" in snapshot.newlyCompressedIds)
    assertTrue(snapshot.newlyCompressedIds.isNotEmpty())
    assertTrue(snapshot.effectivePrompt.contains("<context_density_summary>"))
    assertTrue("pinned" in snapshot.retainedEntryIds)
    assertTrue("safe" in snapshot.retainedEntryIds)
  }

  @Test
  fun temporaryEntriesAreRemovedWithoutEnteringDensitySummary() {
    val temporaryText = "Transient error text that should disappear."
    val snapshot =
      ContextWindowManager.compress(
        input(
          totalTokens = 64,
          messages =
            listOf(
              message("temporary", RetentionPolicy.TEMPORARY, temporaryText),
              message("keep", RetentionPolicy.PINNED, "Keep this."),
            ),
        ),
        forceOne = true,
      )

    assertTrue("temporary" in snapshot.compressedEntryIds)
    assertTrue("temporary" in snapshot.excludedEntryIds)
    assertFalse(snapshot.effectivePrompt.contains(temporaryText))
    assertTrue(snapshot.effectivePrompt.contains("Keep this."))
  }

  @Test
  fun excludedEntriesNeverEnterTheEffectivePrompt() {
    val snapshot =
      ContextWindowManager.snapshot(
        input(
          messages =
            listOf(
              message("excluded", RetentionPolicy.EXCLUDED, "private omitted text"),
              message("included", RetentionPolicy.PINNED, "visible retained text"),
            )
        )
      )

    assertFalse(snapshot.effectivePrompt.contains("private omitted text"))
    assertTrue(snapshot.effectivePrompt.contains("visible retained text"))
    assertTrue("excluded" in snapshot.excludedEntryIds)
  }

  @Test
  fun automaticCompressionHonorsReservedOutputLimitBeforeThreshold() {
    val snapshot =
      ContextWindowManager.compress(
        input(
          totalTokens = 400,
          messages =
            listOf(
              message("old", RetentionPolicy.COMPRESSIBLE, "Older context. ".repeat(40)),
              message("pinned", RetentionPolicy.PINNED, "Required context. ".repeat(10)),
            ),
          settings =
            ContextSettings(
              mode = ContextMode.AUTOMATIC,
              compressionThresholdPercent = 90,
              reservedOutputTokens = 200,
            ),
        )
      )

    assertTrue("old" in snapshot.newlyCompressedIds)
    assertFalse(snapshot.usage.exceedsInputLimit)
  }

  private fun input(
    systemPrompt: String = "",
    draftPrompt: String = "",
    totalTokens: Int = 256,
    messages: List<ManagedContextMessage>,
    settings: ContextSettings =
      ContextSettings(
        mode = ContextMode.ASSISTED,
        compressionThresholdPercent = 70,
        reservedOutputTokens = 32,
      ),
  ): ContextBuildInput =
    ContextBuildInput(
      systemPrompt = systemPrompt,
      totalTokens = totalTokens,
      settings = settings,
      fixedEntries =
        listOf(
          FixedContextEntry(
            id = "system",
            label = "System",
            content = systemPrompt,
          )
        ),
      messages = messages,
      compressedMessageIds = emptySet(),
      draftPrompt = draftPrompt,
      promptBuilder = { history, draft ->
        listOfNotNull(history, draft.takeIf(String::isNotBlank)).joinToString("\n")
      },
    )

  private fun message(
    id: String,
    policy: RetentionPolicy,
    content: String,
  ): ManagedContextMessage =
    ManagedContextMessage(
      id = id,
      label = id,
      content = content,
      policy = policy,
    )
}
