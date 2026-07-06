package com.terminus.edge.light.inference

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import com.terminus.edge.light.model.ModelDescriptor
import com.terminus.edge.light.model.ModelRuntimeType

enum class AgentRole {
  ORCHESTRATOR,
  EXPERT_1,
  EXPERT_2,
  EXPERT_3
}

enum class AgentStatus {
  IDLE,
  GENERATING,
  LOADING,
  ERROR,
  OFFLINE
}

data class AgentState(
  val role: AgentRole,
  val status: AgentStatus,
  val modelName: String? = null,
  val sizeBytes: Long = 0L
)

class LiteRtSwarmEngine(private val context: Context) : AutoCloseable {
  private val engines = ConcurrentHashMap<AgentRole, LocalModelRuntime>()
  private val _agentStates = MutableStateFlow<Map<AgentRole, AgentState>>(emptyMap())
  val agentStates: StateFlow<Map<AgentRole, AgentState>> = _agentStates.asStateFlow()
  
  private val generationMutex = Mutex()

  init {
    // Initialize default states
    _agentStates.value = AgentRole.values().associateWith { role ->
      AgentState(role, AgentStatus.OFFLINE)
    }
  }

  fun load(role: AgentRole, model: ModelDescriptor, systemPrompt: String, settings: GenerationSettings) {
    updateState(role) { it.copy(status = AgentStatus.LOADING, modelName = model.displayName, sizeBytes = model.sizeBytes) }
    try {
      val expectedClass =
        when (model.runtimeType) {
          ModelRuntimeType.LITERT_LM -> LiteRtChatEngine::class
          ModelRuntimeType.GGUF -> GgufRuntime::class
        }
      val existing = engines[role]
      val engine =
        if (existing != null && existing::class == expectedClass) {
          existing
        } else {
          existing?.close()
          when (model.runtimeType) {
            ModelRuntimeType.LITERT_LM -> LiteRtChatEngine(context)
            ModelRuntimeType.GGUF -> GgufRuntime(context)
          }.also { engines[role] = it }
        }
      engine.load(model, systemPrompt, settings)
      updateState(role) { it.copy(status = AgentStatus.IDLE) }
    } catch (e: Throwable) {
      updateState(role) { it.copy(status = AgentStatus.ERROR) }
      throw e
    }
  }
  
  fun unload(role: AgentRole) {
    engines[role]?.close()
    engines.remove(role)
    updateState(role) { it.copy(status = AgentStatus.OFFLINE, modelName = null, sizeBytes = 0L) }
  }
  
  fun resetConversation(role: AgentRole) {
    engines[role]?.resetConversation()
  }

  fun resetAllConversations() {
    engines.values.forEach { it.resetConversation() }
  }

  fun capabilities(role: AgentRole = AgentRole.ORCHESTRATOR): RuntimeCapabilities =
    engines[role]?.capabilities ?: RuntimeCapabilities(vision = false)

  fun metadata(role: AgentRole = AgentRole.ORCHESTRATOR): RuntimeMetadata? =
    engines[role]?.metadata

  suspend fun generate(
    role: AgentRole,
    prompt: String,
    imageBytes: List<ByteArray> = emptyList(),
    onChunk: (String) -> Unit
  ): String {
    val engine = engines[role] ?: error("Agent ${role.name} is not loaded.")
    
    // We lock generation so we don't have multiple agents trying to use the CPU heavily at the precise same time if possible, 
    // though this could be relaxed if we want true parallel generation.
    return generationMutex.withLock {
      updateState(role) { it.copy(status = AgentStatus.GENERATING) }
      try {
        val response = engine.generate(prompt, imageBytes, onChunk)
        updateState(role) { it.copy(status = AgentStatus.IDLE) }
        response
      } catch (e: Throwable) {
        updateState(role) { it.copy(status = AgentStatus.IDLE) } // reset to idle on error
        throw e
      }
    }
  }

  fun cancel(role: AgentRole? = null) {
    if (role != null) {
      engines[role]?.cancel()
      updateState(role) { it.copy(status = AgentStatus.IDLE) }
    } else {
      engines.values.forEach { it.cancel() }
      engines.keys.forEach { updateState(it) { state -> state.copy(status = AgentStatus.IDLE) } }
    }
  }

  override fun close() {
    engines.values.forEach { runCatching { it.close() } }
    engines.clear()
    _agentStates.value = AgentRole.values().associateWith { AgentState(it, AgentStatus.OFFLINE) }
  }

  private fun updateState(role: AgentRole, update: (AgentState) -> AgentState) {
    val currentStates = _agentStates.value.toMutableMap()
    val currentState = currentStates[role] ?: AgentState(role, AgentStatus.OFFLINE)
    currentStates[role] = update(currentState)
    _agentStates.value = currentStates
  }
}
