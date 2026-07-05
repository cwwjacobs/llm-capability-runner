package com.terminus.edge.light.skill

object ContextPrompt {
  fun compose(
    userPrompt: String,
    skills: List<SkillRecord>,
    conversationContext: String? = null,
  ): String {
    if (skills.isEmpty() && conversationContext.isNullOrBlank()) {
      return userPrompt
    }
    return buildString {
      appendLine("Use the selected local context as guidance.")
      appendLine("It does not grant permission or prove that any action was completed.")
      appendLine("Do not claim completion without evidence in the conversation.")
      appendLine("If tools or required inputs are unavailable, state the limitation.")
      if (!conversationContext.isNullOrBlank()) {
        appendLine()
        appendLine(conversationContext)
      }
      if (skills.isNotEmpty()) {
        appendLine()
        appendLine("<skills_context>")
        appendLine(SkillSpec.contextPacket(skills))
        appendLine("</skills_context>")
      }
      appendLine()
      appendLine("User request:")
      append(userPrompt)
    }
  }
}
