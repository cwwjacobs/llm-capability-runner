package com.terminus.edge.light.trace

import com.terminus.edge.light.model.ModelDescriptor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceLedgerTest {
  @Test
  fun curatedExportIncludesKeptAndEditedCompletionsOnly() {
    val fixture = newFixture()
    fixture.appendCompleted("keep-id", "kept response")
    fixture.appendCompleted("reject-id", "rejected response")
    fixture.appendCompleted("edit-id", "original response")
    fixture.ledger.appendReview(review("keep-id", ReviewDecision.KEEP))
    fixture.ledger.appendReview(review("reject-id", ReviewDecision.REJECT))
    fixture.ledger.appendReview(
      review(
        traceId = "edit-id",
        decision = ReviewDecision.EDITED,
        corrected = "corrected response",
        rubric = ReviewRubric(correctness = 5, usefulness = 4),
      )
    )

    val output = ByteArrayOutputStream()
    val count = fixture.ledger.exportCurated(output)
    val text = output.toString(Charsets.UTF_8)

    assertEquals(2, count)
    assertTrue(text.contains("\"schema_version\":\"edge-training.v2\""))
    assertTrue(text.contains("kept response"))
    assertTrue(text.contains("corrected response"))
    assertFalse(text.contains("rejected response"))
    assertFalse(text.contains("original response"))
    assertTrue(text.contains("\"rights_status\":\"unverified_for_training\""))
    assertTrue(text.contains("\"correctness\":5"))
  }

  @Test
  fun latestReviewDecisionWinsAndSupersedesPriorReview() {
    val fixture = newFixture()
    fixture.appendCompleted("trace-id", "response")
    fixture.ledger.appendReview(review("trace-id", ReviewDecision.REJECT))
    fixture.ledger.appendReview(review("trace-id", ReviewDecision.KEEP))

    val output = ByteArrayOutputStream()
    fixture.ledger.exportRaw(output)
    val raw = output.toString(Charsets.UTF_8)

    assertEquals(1, fixture.ledger.exportCurated(ByteArrayOutputStream()))
    assertTrue(raw.contains("\"supersedes_event_id\""))
  }

  @Test
  fun lifecycleStatsSeparateCompletedFailedCancelledAndUnreviewed() {
    val fixture = newFixture()
    fixture.appendCompleted("complete-id", "response")
    fixture.ledger.appendInferenceStarted(started("failed-id"))
    fixture.ledger.appendInferenceFailed(outcome("failed-id", "runtime_error"))
    fixture.ledger.appendInferenceStarted(started("cancel-id"))
    fixture.ledger.appendInferenceCancelled(outcome("cancel-id", "operator_cancelled"))

    val stats = fixture.ledger.stats()

    assertEquals(3, stats.inferenceAttempts)
    assertEquals(1, stats.completed)
    assertEquals(1, stats.failed)
    assertEquals(1, stats.cancelled)
    assertEquals(1, stats.unreviewed)
    assertEquals("verified", stats.integrityStatus)
    assertNotNull(stats.chainHeadSha256)
  }

  @Test
  fun traceRecordsContextAccountingAndCompressionDecisions() {
    val fixture = newFixture()
    fixture.ledger.appendInferenceStarted(
      started("trace-id")
        .copy(
          contextManagement =
            ContextManagementTrace(
              totalTokens = 4096,
              estimatedInputTokens = 2048,
              inputCharacters = 8192,
              reservedOutputTokens = 1024,
              estimateMethod = "characters_divided_by_4",
              mode = "automatic",
              compressionThresholdPercent = 70,
              includedEntryIds = listOf("system", "message-new"),
              excludedEntryIds = listOf("message-temp"),
              compressedEntryIds = listOf("message-old"),
              retainedEntryIds = listOf("system"),
              compressionOperations = listOf("Density-compressed entry message-old."),
            )
        )
    )

    val output = ByteArrayOutputStream()
    fixture.ledger.exportRaw(output)
    val raw = output.toString(Charsets.UTF_8)

    assertTrue(raw.contains("\"management\""))
    assertTrue(raw.contains("\"estimated_input_tokens\":2048"))
    assertTrue(raw.contains("\"compressed_entry_ids\":[\"message-old\"]"))
    assertTrue(raw.contains("\"mode\":\"automatic\""))
  }

  @Test
  fun tamperedEventFailsHashChainVerification() {
    val fixture = newFixture()
    fixture.appendCompleted("trace-id", "original response")
    val text = fixture.ledgerFile.readText(Charsets.UTF_8)
    fixture.ledgerFile.writeText(
      text.replace("original response", "changed response"),
      Charsets.UTF_8,
    )

    assertEquals("failed", fixture.ledger.stats().integrityStatus)
  }

  @Test(expected = IllegalArgumentException::class)
  fun curatedExportRejectsTamperedLedger() {
    val fixture = newFixture()
    fixture.appendCompleted("trace-id", "original response")
    fixture.ledger.appendReview(review("trace-id", ReviewDecision.KEEP))
    fixture.ledgerFile.writeText(
      fixture.ledgerFile.readText(Charsets.UTF_8).replace("original response", "changed response"),
      Charsets.UTF_8,
    )

    fixture.ledger.exportCurated(ByteArrayOutputStream())
  }

  @Test
  fun malformedLinesAreReportedButDoNotBreakCuratedExport() {
    val fixture = newFixture(initialText = "not-json\n")
    fixture.appendCompleted("trace-id", "response")
    fixture.ledger.appendReview(review("trace-id", ReviewDecision.KEEP))

    val output = ByteArrayOutputStream()

    assertEquals(1, fixture.ledger.exportCurated(output))
    assertEquals(1, fixture.ledger.stats().malformedLines)
    assertEquals("verified_with_malformed_lines", fixture.ledger.stats().integrityStatus)
  }

  @Test
  fun replayBundleIncludesLedgerContextSnapshotsModelAndManifest() {
    val fixture = newFixture()
    val skill =
      fixture.artifacts.snapshotText(
        kind = "skills",
        logicalId = "skill-one",
        extension = "md",
        mediaType = "text/markdown",
        content = "---\nname: One\ndescription: Test\n---\nDo one thing.\n",
      )
    val modelBytes = "small-test-model".toByteArray(Charsets.UTF_8)
    val modelSha = TraceIntegrity.sha256(modelBytes)
    val modelPath = fixture.artifactRoot.resolve("models/$modelSha.litertlm")
    requireNotNull(modelPath.parentFile).mkdirs()
    modelPath.writeBytes(modelBytes)
    val model =
      ModelSnapshot(
        displayName = "test-model",
        sha256 = modelSha,
        sizeBytes = modelBytes.size.toLong(),
        importedAtMs = 1,
      )
    val modelSnapshot =
      model.copy(
        artifact =
          TraceArtifactRef(
            kind = "models",
            logicalId = "test-model",
            sha256 = modelSha,
            sizeBytes = modelBytes.size.toLong(),
            mediaType = "application/octet-stream",
            storagePath = "models/$modelSha.litertlm",
          ),
      )
    fixture.ledger.appendInferenceStarted(
      started(traceId = "trace-id", model = model, skillArtifacts = listOf(skill))
    )
    fixture.ledger.appendInferenceCompleted(completed("trace-id", "response"))
    fixture.ledger.appendReview(review("trace-id", ReviewDecision.KEEP))
    fixture.ledger.appendModelSnapshot(
      snapshot = modelSnapshot,
      sessionId = "session",
      createdAtMs = 4,
      appVersion = "test",
    )

    val output = ByteArrayOutputStream()
    val result = fixture.ledger.exportReplay(output)
    val entries = zipEntries(output.toByteArray())

    assertEquals(1, result.traceCount)
    assertEquals(2, result.artifactCount)
    assertEquals(1, result.modelCount)
    assertTrue(entries.contains("events.jsonl"))
    assertTrue(entries.contains("artifacts/${skill.storagePath}"))
    assertTrue(entries.contains("artifacts/models/$modelSha.litertlm"))
    assertTrue(entries.contains("manifest.json"))
  }

  @Test
  fun traceIncludesExactImageArtifact() {
    val fixture = newFixture()
    val image =
      fixture.artifacts.snapshotBytes(
        kind = "images",
        logicalId = "photo.png",
        extension = "png",
        mediaType = "image/png",
        bytes = "image-bytes".toByteArray(Charsets.UTF_8),
      )
    fixture.ledger.appendInferenceStarted(
      started(traceId = "trace-id").copy(imageArtifacts = listOf(image))
    )

    val output = ByteArrayOutputStream()
    fixture.ledger.exportRaw(output)

    assertTrue(output.toString(Charsets.UTF_8).contains("\"images\":["))
    assertTrue(output.toString(Charsets.UTF_8).contains(image.sha256))
  }

  @Test
  fun inferenceStartedRecordsModelMetadataWithoutBinarySnapshot() {
    val fixture = newFixture()
    fixture.ledger.appendInferenceStarted(started(traceId = "trace-id"))

    val output = ByteArrayOutputStream()
    fixture.ledger.exportRaw(output)
    val raw = output.toString(Charsets.UTF_8)

    assertTrue(raw.contains("\"model\""))
    assertTrue(raw.contains("\"sha256\":\"${"a".repeat(64)}\""))
    assertFalse(raw.contains("\"snapshot\""))
  }

  @Test
  fun modelSnapshotPreservesExactBytesUnderContentHash() {
    val fixture = newFixture()
    val source = requireNotNull(fixture.artifactRoot.parentFile).resolve("active-model.litertlm")
    source.writeText("exact model bytes", Charsets.UTF_8)
    val sha256 = TraceIntegrity.sha256(source)

    val snapshot =
      fixture.artifacts.snapshotModel(
        ModelDescriptor(
          displayName = "model",
          path = source.absolutePath,
          sha256 = sha256,
          sizeBytes = source.length(),
          importedAtMs = 1,
        )
    )

    assertEquals(sha256, snapshot.sha256)
    val artifact = requireNotNull(snapshot.artifact)
    assertEquals(source.readBytes().toList(), fixture.artifacts.resolve(artifact).readBytes().toList())
  }

  @Test(expected = IllegalArgumentException::class)
  fun modelSnapshotRejectsIncorrectDescriptorHash() {
    val fixture = newFixture()
    val source = requireNotNull(fixture.artifactRoot.parentFile).resolve("active-model.litertlm")
    source.writeText("exact model bytes", Charsets.UTF_8)

    fixture.artifacts.snapshotModel(
      ModelDescriptor(
        displayName = "model",
        path = source.absolutePath,
        sha256 = "0".repeat(64),
        sizeBytes = source.length(),
        importedAtMs = 1,
      )
    )
  }

  @Test(expected = IllegalArgumentException::class)
  fun contentAddressedArtifactRejectsSameSizeTampering() {
    val fixture = newFixture()
    val reference =
      fixture.artifacts.snapshotText(
        kind = "skills",
        logicalId = "skill",
        extension = "md",
        mediaType = "text/markdown",
        content = "original",
      )
    fixture.artifactRoot.resolve(reference.storagePath).writeText("tampered", Charsets.UTF_8)

    fixture.artifacts.snapshotText(
      kind = "skills",
      logicalId = "skill",
      extension = "md",
      mediaType = "text/markdown",
      content = "original",
    )
  }

  @Test
  fun legacyInferenceRemainsCuratableWithoutClaimingV2Provenance() {
    val fixture =
      newFixture(
        initialText =
          """{"schema_version":"edge-trace.v1","event_type":"inference","event_id":"old-event","trace_id":"trace-id","prompt":"prompt","response":"response","system_prompt":"system","model":{"name":"old"},"generation":{}}
"""
      )
    fixture.ledger.appendReview(review("trace-id", ReviewDecision.KEEP))
    val output = ByteArrayOutputStream()

    assertEquals(1, fixture.ledger.exportCurated(output))
    assertTrue(output.toString(Charsets.UTF_8).contains("\"source_trace_schema\":\"edge-trace.v1\""))
    assertEquals("partial_legacy", fixture.ledger.stats().integrityStatus)
  }

  @Test(expected = IllegalArgumentException::class)
  fun replayRejectsLegacyTraceWithoutItsModelSnapshot() {
    val sha256 = "b".repeat(64)
    val fixture =
      newFixture(
        initialText =
          """{"schema_version":"edge-trace.v1","event_type":"inference","event_id":"old-event","trace_id":"trace-id","prompt":"prompt","response":"response","system_prompt":"system","model":{"name":"old","sha256":"$sha256"},"generation":{}}
"""
      )

    fixture.ledger.exportReplay(ByteArrayOutputStream())
  }

  @Test(expected = IllegalArgumentException::class)
  fun editedReviewRequiresCorrection() {
    newFixture().ledger.appendReview(review("trace-id", ReviewDecision.EDITED, ""))
  }

  private fun newFixture(initialText: String = ""): Fixture {
    val directory = Files.createTempDirectory("edge-trace-test").toFile()
    val ledgerFile = directory.resolve("trace_events.jsonl")
    if (initialText.isNotEmpty()) ledgerFile.writeText(initialText, Charsets.UTF_8)
    val artifactRoot = directory.resolve("artifacts")
    val artifacts = TraceArtifactStore(artifactRoot)
    return Fixture(
      ledger = TraceLedger(ledgerFile, artifacts),
      ledgerFile = ledgerFile,
      artifactRoot = artifactRoot,
      artifacts = artifacts,
    )
  }

  private fun started(
    traceId: String,
    model: ModelSnapshot = modelSnapshot(),
    skillArtifacts: List<TraceArtifactRef> = emptyList(),
  ): InferenceStartedTrace =
    InferenceStartedTrace(
      traceId = traceId,
      sessionId = "session",
      createdAtMs = 1,
      turnIndex = 0,
      parentTraceId = null,
      historyState = "complete_application_history",
      userPrompt = "raw prompt",
      effectivePrompt = "effective prompt",
      systemPrompt = "system",
      historyArtifact = null,
      model = model,
      maxTokens = 128,
      topK = 40,
      topP = 0.95,
      temperature = 0.7,
      imageInputEnabled = false,
      appVersion = "test",
      runtimeName = "litertlm-android",
      runtimeVersion = "test",
      backend = "cpu",
      skillArtifacts = skillArtifacts,
    )

  private fun completed(traceId: String, response: String): InferenceCompletedTrace =
    InferenceCompletedTrace(
      traceId = traceId,
      sessionId = "session",
      createdAtMs = 2,
      response = response,
      latencyMs = 10,
      timeToFirstChunkMs = 3,
      chunkCount = 2,
    )

  private fun outcome(traceId: String, category: String): InferenceOutcomeTrace =
    InferenceOutcomeTrace(
      traceId = traceId,
      sessionId = "session",
      createdAtMs = 2,
      latencyMs = 10,
      timeToFirstChunkMs = null,
      chunkCount = 0,
      partialResponse = "",
      category = category,
      message = null,
    )

  private fun review(
    traceId: String,
    decision: ReviewDecision,
    corrected: String? = null,
    rubric: ReviewRubric = ReviewRubric(),
  ): ReviewTrace =
    ReviewTrace(
      traceId = traceId,
      createdAtMs = 3,
      decision = decision,
      correctedResponse = corrected,
      note = "operator note",
      tags = listOf("useful"),
      rubric = rubric,
    )

  private fun modelSnapshot(): ModelSnapshot {
    val sha = "a".repeat(64)
    return ModelSnapshot(
      displayName = "model",
      sha256 = sha,
      sizeBytes = 42,
      importedAtMs = 1,
    )
  }

  private fun zipEntries(bytes: ByteArray): Set<String> {
    val names = mutableSetOf<String>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        names += entry.name
        while (zip.read() >= 0) {
          // Drain each entry so the next header can be read.
        }
        zip.closeEntry()
      }
    }
    return names
  }

  private fun Fixture.appendCompleted(traceId: String, response: String) {
    ledger.appendInferenceStarted(started(traceId))
    ledger.appendInferenceCompleted(completed(traceId, response))
  }

  private data class Fixture(
    val ledger: TraceLedger,
    val ledgerFile: java.io.File,
    val artifactRoot: java.io.File,
    val artifacts: TraceArtifactStore,
  )
}
