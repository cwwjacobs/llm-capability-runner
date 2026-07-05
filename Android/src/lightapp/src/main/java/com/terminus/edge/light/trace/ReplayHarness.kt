package com.terminus.edge.light.trace

import java.io.OutputStream

data class ReplayManifest(
  val version: String = "1.0",
  val generator: String = "LLM Capability Runner",
  val timestampMs: Long,
  val activePersona: String?,
  val selectedMemories: List<String>,
  val attachedSkills: List<String>
)

class ReplayHarness(private val traceLedger: TraceLedger) {
  fun packageReplay(output: OutputStream, manifest: ReplayManifest): ReplayExportResult {
    // In the future, we could serialize the manifest into the zip here.
    // For now, we delegate to TraceLedger's robust export.
    return traceLedger.exportReplay(output)
  }
}
