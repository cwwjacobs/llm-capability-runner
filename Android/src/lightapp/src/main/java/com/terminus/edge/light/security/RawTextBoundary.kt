package com.terminus.edge.light.security

object RawTextBoundary {
  /**
   * Cleanses incoming raw text (e.g., from the clipboard or UI) to prevent injection
   * or misinterpretation by the LLM by escaping control characters.
   */
  fun sanitize(input: String): String {
    if (input.isBlank()) return input
    
    // Remove or escape harmful invisible characters, null bytes, etc.
    return input.replace("\u0000", "")
      .replace(Regex("[\\p{C}&&[^\\r\\n\\t]]"), "")
      .trim()
  }
}
