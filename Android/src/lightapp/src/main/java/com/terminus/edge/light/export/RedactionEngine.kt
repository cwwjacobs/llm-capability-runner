package com.terminus.edge.light.export

object RedactionEngine {
  private val SECRET_PATTERNS = listOf(
    Regex("""sk-[a-zA-Z0-9]{32,}"""), // OpenAI/generic secret keys
    Regex("""hf_[a-zA-Z0-9]{34,}""")  // HuggingFace tokens
  )
  
  private val CHAIN_OF_THOUGHT_PATTERN = Regex("""<chain_of_thought>.*?</chain_of_thought>""", RegexOption.DOT_MATCHES_ALL)

  fun redact(input: String): String {
    var redacted = input
    redacted = CHAIN_OF_THOUGHT_PATTERN.replace(redacted, "")
    for (pattern in SECRET_PATTERNS) {
      redacted = pattern.replace(redacted, "[REDACTED_SECRET]")
    }
    return redacted.trim()
  }
}
