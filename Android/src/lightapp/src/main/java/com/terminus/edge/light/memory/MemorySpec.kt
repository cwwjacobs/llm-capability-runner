package com.terminus.edge.light.memory

import java.util.UUID

object MemorySpec {
  fun parse(markdown: String): MemoryRecord {
    var inFrontmatter = false
    var name = "Untitled Memory"
    val tags = mutableListOf<String>()
    
    val lines = markdown.lines()
    for (line in lines) {
      if (line.trim() == "---") {
        if (!inFrontmatter && lines.indexOf(line) == 0) {
          inFrontmatter = true
          continue
        } else if (inFrontmatter) {
          inFrontmatter = false
          break
        }
      }
      if (inFrontmatter) {
        if (line.startsWith("name:")) {
          name = line.substringAfter("name:").trim()
        } else if (line.startsWith("tags:")) {
          val tagList = line.substringAfter("tags:").split(",").map { it.trim() }.filter { it.isNotEmpty() }
          tags.addAll(tagList)
        }
      }
    }
    
    val id = UUID.nameUUIDFromBytes(name.toByteArray()).toString()
    
    return MemoryRecord(
      id = id,
      name = name,
      content = markdown,
      tags = tags,
      source = markdown
    )
  }

  fun make(name: String, content: String, tags: List<String>): MemoryRecord {
    val source = buildString {
      appendLine("---")
      appendLine("name: $name")
      if (tags.isNotEmpty()) {
        appendLine("tags: ${tags.joinToString(", ")}")
      }
      appendLine("---")
      appendLine()
      appendLine(content)
    }
    return parse(source)
  }
}
