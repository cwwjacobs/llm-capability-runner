package com.terminus.edge.light.memory

object MemoryHydrator {
  fun hydrate(memories: List<MemoryRecord>, draftPrompt: String): String {
    if (memories.isEmpty()) return draftPrompt
    val memoryBlock = buildString {
      appendLine("<memories>")
      for (memory in memories) {
        appendLine("<memory name=\"${memory.name}\">")
        appendLine(memory.content)
        appendLine("</memory>")
      }
      appendLine("</memories>")
    }
    return "$memoryBlock\n\n$draftPrompt"
  }
}
