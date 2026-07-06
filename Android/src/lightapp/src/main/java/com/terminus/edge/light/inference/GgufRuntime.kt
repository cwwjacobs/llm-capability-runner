package com.terminus.edge.light.inference

import android.content.Context
import com.arm.aichat.internal.InferenceEngineImpl
import com.terminus.edge.light.BuildConfig
import com.terminus.edge.light.model.ModelDescriptor
import com.terminus.edge.light.model.ModelRuntimeType
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

class GgufRuntime(context: Context) : LocalModelRuntime {
  private val bridge = InferenceEngineImpl(context.applicationContext)
  private var activeModel: ModelDescriptor? = null
  private var activeSystemPrompt = ""
  private var activeSettings = GenerationSettings()
  @Volatile private var activeJob: Job? = null

  override var capabilities = RuntimeCapabilities(vision = false)
    private set

  override var metadata =
    RuntimeMetadata(
      runtimeType = ModelRuntimeType.GGUF,
      runtimeName = "llama.cpp-android",
      runtimeVersion = BuildConfig.GGUF_RUNTIME_VERSION,
    )
    private set

  override fun load(
    model: ModelDescriptor,
    systemPrompt: String,
    settings: GenerationSettings,
  ) {
    require(model.runtimeType == ModelRuntimeType.GGUF) { "Select a GGUF model." }
    runBlocking { bridge.loadModel(model.path, systemPrompt, model.visionProjectorPath) }
    activeModel = model
    activeSystemPrompt = systemPrompt
    activeSettings = settings
    metadata =
      metadata.copy(
        modelArchitecture = model.architecture,
        quantization = model.quantization,
        contextTokens = model.supportedContextTokens,
      )
    // Text generation is live. Vision is enabled only when the native mtmd bridge is present.
    capabilities = RuntimeCapabilities(vision = model.visionProjectorPath != null)
  }

  override fun resetConversation() {
    val model = activeModel ?: error("Load a GGUF model first.")
    runBlocking { bridge.reset(model.path, activeSystemPrompt, model.visionProjectorPath) }
  }

  override suspend fun generate(
    prompt: String,
    imageBytes: List<ByteArray>,
    onChunk: (String) -> Unit,
  ): String {
    require(imageBytes.size <= 1) { "GGUF runtime supports one image per turn." }
    require(imageBytes.isEmpty() || capabilities.vision) {
      "This GGUF model does not have a compatible multimodal projector loaded."
    }
    val job = currentCoroutineContext()[Job]
    activeJob = job
    return try {
      bridge.generate(
        prompt = prompt,
        predictLength = (activeSettings.maxTokens / 4).coerceIn(128, 2048),
        imageBytes = imageBytes.firstOrNull(),
        onToken = onChunk,
      )
    } finally {
      if (activeJob == job) activeJob = null
    }
  }

  override fun cancel() {
    activeJob?.cancel()
  }

  override fun close() {
    activeJob?.cancel()
    bridge.close()
  }
}
