package com.terminus.edge.light.inference

import com.terminus.edge.light.model.ModelDescriptor
import com.terminus.edge.light.model.ModelRuntimeType

data class RuntimeCapabilities(
  val vision: Boolean,
  val streaming: Boolean = true,
  val maxImagesPerTurn: Int = if (vision) 1 else 0,
)

data class RuntimeMetadata(
  val runtimeType: ModelRuntimeType,
  val runtimeName: String,
  val runtimeVersion: String,
  val modelArchitecture: String? = null,
  val quantization: String? = null,
  val contextTokens: Int? = null,
)

interface LocalModelRuntime : AutoCloseable {
  val capabilities: RuntimeCapabilities
  val metadata: RuntimeMetadata

  fun load(model: ModelDescriptor, systemPrompt: String, settings: GenerationSettings)

  fun resetConversation()

  suspend fun generate(
    prompt: String,
    imageBytes: List<ByteArray> = emptyList(),
    onChunk: (String) -> Unit,
  ): String

  fun cancel()
}
