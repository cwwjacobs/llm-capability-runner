package com.terminus.edge.light.persona

import android.util.Log

object GateController {
  enum class GateDecision {
    ALLOW,
    DENY,
    PROMPT_OPERATOR
  }

  fun evaluateCapability(persona: Persona, capabilityName: String): GateDecision {
    if (capabilityName in persona.allowedCapabilities) {
      return GateDecision.ALLOW
    }
    
    // Fallback: If a capability is not explicitly allowed, we default to PROMPT_OPERATOR
    // for safety, ensuring the user has the final say.
    Log.d("GateController", "Capability $capabilityName requires Operator approval.")
    return GateDecision.PROMPT_OPERATOR
  }
}
