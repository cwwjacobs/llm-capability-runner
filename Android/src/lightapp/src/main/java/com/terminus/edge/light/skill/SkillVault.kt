package com.terminus.edge.light.skill

import java.io.File

class SkillVault(private val root: File) {
  init {
    require(root.mkdirs() || root.isDirectory) { "Could not create the Skill vault." }
  }

  fun list(): List<SkillRecord> =
    root
      .listFiles { file -> file.isFile && file.extension.equals("md", ignoreCase = true) }
      .orEmpty()
      .mapNotNull { file -> runCatching { SkillSpec.parse(file.readText(Charsets.UTF_8)) }.getOrNull() }
      .sortedBy { it.name.lowercase() }

  fun importMarkdown(markdown: String): SkillRecord {
    val record = SkillSpec.parse(markdown)
    return saveNew(record)
  }

  fun seedMarkdown(markdown: String): SkillRecord {
    val record = SkillSpec.parse(markdown)
    val target = File(root, "${record.id}.md")
    if (!target.exists()) target.writeText(record.source, Charsets.UTF_8)
    return record
  }

  fun add(name: String, description: String, instructions: String): SkillRecord {
    val record = SkillSpec.make(name, description, instructions)
    return saveNew(record)
  }

  private fun saveNew(record: SkillRecord): SkillRecord {
    val target = File(root, "${record.id}.md")
    if (target.exists()) {
      val existing =
        runCatching { SkillSpec.parse(target.readText(Charsets.UTF_8)) }.getOrNull()
      if (existing?.source == record.source) return existing
      throw IllegalArgumentException(
        "Skill '${record.name}' conflicts with existing Skill id '${record.id}'. " +
          "Rename it or remove the existing Skill first."
      )
    }
    target.writeText(record.source, Charsets.UTF_8)
    return record
  }
}
