package com.terminus.edge.light.spine

import java.nio.file.Files
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSpineTest {
  @Test
  fun appendsParsesAndArchivesWithoutDeletion() {
    val root = Files.createTempDirectory("runtime-spine-test").toFile()
    val file = root.resolve("runtime-spine.jsonl")
    val spine = RuntimeSpine(file)

    spine.append(
      type = SpineRecordType.TRACE,
      sessionId = "session",
      payload = buildJsonObject { put("response", "hello") },
    )
    spine.append(
      type = SpineRecordType.CORRECTION_TRACE,
      sessionId = "session",
      payload = buildJsonObject { put("decision", "edited") },
    )

    val result = spine.read(sessionId = "session")
    assertEquals(2, result.records.size)
    assertTrue(result.integrityValid)
    assertEquals(0, result.malformedLines)

    val archived = requireNotNull(spine.archive())
    assertTrue(archived.isFile)
    assertTrue(!file.exists())
  }

  @Test
  fun toleratesMalformedLines() {
    val root = Files.createTempDirectory("runtime-spine-malformed").toFile()
    val file = root.resolve("runtime-spine.jsonl")
    file.writeText("not-json\n")
    val result = RuntimeSpine(file).read()
    assertEquals(1, result.malformedLines)
  }
}
