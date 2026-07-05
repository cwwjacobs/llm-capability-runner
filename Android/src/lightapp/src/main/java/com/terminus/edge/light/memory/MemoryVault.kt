package com.terminus.edge.light.memory

import java.io.File

class MemoryVault(private val root: File) {
  init {
    require(root.mkdirs() || root.isDirectory) { "Could not create the Memory vault." }
  }

  fun list(): List<MemoryRecord> =
    root
      .listFiles { file -> file.isFile && file.extension.equals("md", ignoreCase = true) }
      .orEmpty()
      .mapNotNull { file -> runCatching { MemorySpec.parse(file.readText(Charsets.UTF_8)) }.getOrNull() }
      .sortedBy { it.name.lowercase() }

  fun add(name: String, content: String, tags: List<String>): MemoryRecord {
    val record = MemorySpec.make(name, content, tags)
    val target = File(root, "${record.id}.md")
    target.writeText(record.source, Charsets.UTF_8)
    return record
  }
}
