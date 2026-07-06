package com.terminus.edge.light.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowDraftCodecTest {
  @Test
  fun roundTripPreservesWorkflowFields() {
    val draft =
      WorkflowDraft(
        id = "workflow-1",
        name = "Inspect and summarize",
        goal = "Produce a local report.",
        steps = listOf("Inspect inputs", "Write report"),
        updatedAtMs = 42L,
      )

    assertEquals(listOf(draft), WorkflowDraftCodec.decode(WorkflowDraftCodec.encode(listOf(draft))))
  }

  @Test
  fun malformedInputReturnsEmptyList() {
    assertTrue(WorkflowDraftCodec.decode("[not-json").isEmpty())
  }
}
