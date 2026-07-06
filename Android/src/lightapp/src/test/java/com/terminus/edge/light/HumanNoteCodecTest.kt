package com.terminus.edge.light

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HumanNoteCodecTest {
  @Test
  fun roundTripPreservesNoteFields() {
    val note =
      HumanNote(
        id = "note-1",
        title = "Release check",
        body = "Verify the local APK.",
        updatedAtMs = 42L,
      )

    assertEquals(listOf(note), HumanNoteCodec.decode(HumanNoteCodec.encode(listOf(note))))
  }

  @Test
  fun malformedInputReturnsEmptyList() {
    assertTrue(HumanNoteCodec.decode("{not-json").isEmpty())
  }
}
