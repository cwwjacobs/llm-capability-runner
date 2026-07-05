package com.terminus.edge.light.parsing

object RawTextBoundary {
  fun wrapExternalData(content: String, sourceName: String = "External Data"): String {
    return buildString {
      appendLine("<raw_input source=\"$sourceName\">")
      appendLine(content)
      appendLine("</raw_input>")
    }
  }

  fun extractCandidateInstructions(content: String): List<String> {
    // If the model tries to extract actionable instructions from raw input,
    // they must be presented to the Operator before execution.
    // For now, this is a placeholder boundary.
    val candidates = mutableListOf<String>()
    
    // Simplistic extraction based on hypothetical model tags
    val regex = Regex("""<candidate_instruction>(.*?)</candidate_instruction>""", RegexOption.DOT_MATCHES_ALL)
    for (match in regex.findAll(content)) {
      candidates.add(match.groupValues[1].trim())
    }
    
    return candidates
  }
}
