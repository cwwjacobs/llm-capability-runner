package com.terminus.edge.light.skill

data class SkillRecord(
  val id: String,
  val name: String,
  val description: String,
  val instructions: String,
  val source: String,
)

object SkillSpec {
  fun parse(markdown: String): SkillRecord {
    val normalized = markdown.replace("\r\n", "\n").replace('\r', '\n').trim()
    require(normalized.startsWith("---\n")) {
      "Skill must begin with YAML frontmatter."
    }
    val lines = normalized.lines()
    val endOffset = lines.drop(1).indexOfFirst { it.trim() == "---" }
    val end = if (endOffset >= 0) endOffset + 1 else -1
    require(end > 1) { "Skill frontmatter is not closed." }

    var name = ""
    var description = ""
    lines.subList(1, end).forEach { line ->
      val trimmed = line.trim()
      when {
        trimmed.startsWith("name:") -> name = unquote(trimmed.substringAfter("name:").trim())
        trimmed.startsWith("description:") ->
          description = unquote(trimmed.substringAfter("description:").trim())
      }
    }
    val instructions = lines.drop(end + 1).joinToString("\n").trim()
    require(name.isNotBlank()) { "Skill frontmatter requires a name." }
    require(description.isNotBlank()) { "Skill frontmatter requires a description." }
    require(instructions.isNotBlank()) { "Skill instructions are empty." }

    return SkillRecord(
      id = slug(name),
      name = name,
      description = description,
      instructions = instructions,
      source = normalized + "\n",
    )
  }

  fun make(name: String, description: String, instructions: String): SkillRecord {
    val cleanName = name.trim()
    val cleanDescription = description.trim()
    val cleanInstructions = instructions.trim()
    require(cleanName.isNotEmpty()) { "Skill name is required." }
    require(cleanDescription.isNotEmpty()) { "Skill description is required." }
    require(cleanInstructions.isNotEmpty()) { "Skill instructions are required." }
    require('\n' !in cleanName && "---" !in cleanName) { "Skill name must be one line." }
    require('\n' !in cleanDescription && "---" !in cleanDescription) {
      "Skill description must be one line."
    }
    return parse(
      """
      ---
      name: $cleanName
      description: $cleanDescription
      ---

      $cleanInstructions
      """.trimIndent()
    )
  }

  fun contextPacket(records: List<SkillRecord>): String =
    records.joinToString("\n\n") { record ->
      "## ${record.name} [${record.id}]\n${record.description}\n\n${record.instructions}"
    }

  private fun slug(value: String): String =
    value
      .lowercase()
      .replace(Regex("[^a-z0-9]+"), "-")
      .trim('-')
      .ifEmpty { "skill" }

  private fun unquote(value: String): String =
    if (
      value.length >= 2 &&
        ((value.first() == '"' && value.last() == '"') ||
          (value.first() == '\'' && value.last() == '\''))
    ) {
      value.substring(1, value.lastIndex)
    } else {
      value
    }
}
