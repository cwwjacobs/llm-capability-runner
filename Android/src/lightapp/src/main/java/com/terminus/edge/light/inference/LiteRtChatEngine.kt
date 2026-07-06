package com.terminus.edge.light.inference

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import com.terminus.edge.light.model.ModelDescriptor
import com.terminus.edge.light.model.ModelRuntimeType
import com.terminus.edge.light.BuildConfig

data class GenerationSettings(
  val maxTokens: Int = 4000,
  val topK: Int = 64,
  val topP: Double = 0.95,
  val temperature: Double = 1.0,
  val imageInputEnabled: Boolean = false,
)

class LiteRtChatEngine(private val context: Context) : LocalModelRuntime {
  private var engine: Engine? = null
  private var conversation: Conversation? = null
  private var activeSettings: GenerationSettings? = null
  private var activeSystemPrompt: String = ""

  override var capabilities = RuntimeCapabilities(vision = false)
    private set
  override val metadata =
    RuntimeMetadata(
      runtimeType = ModelRuntimeType.LITERT_LM,
      runtimeName = "litertlm-android",
      runtimeVersion = BuildConfig.LITERT_LM_VERSION,
    )

  override fun load(model: ModelDescriptor, systemPrompt: String, settings: GenerationSettings) {
    close()
    val nextEngine =
      Engine(
        EngineConfig(
          modelPath = model.path,
          backend = Backend.CPU(),
          visionBackend = if (settings.imageInputEnabled) Backend.GPU() else null,
          maxNumTokens = settings.maxTokens,
          maxNumImages = if (settings.imageInputEnabled) 1 else null,
          cacheDir = context.cacheDir.absolutePath,
        )
      )
    try {
      nextEngine.initialize()
      engine = nextEngine
      activeSettings = settings
      activeSystemPrompt = systemPrompt
      capabilities = RuntimeCapabilities(vision = settings.imageInputEnabled)
      resetConversation()
    } catch (error: Throwable) {
      runCatching { nextEngine.close() }
      engine = null
      activeSettings = null
      activeSystemPrompt = ""
      throw error
    }
  }

  override fun resetConversation() {
    val activeEngine = engine ?: error("Import a model first.")
    val settings = activeSettings ?: error("Model settings are unavailable.")
    runCatching { conversation?.close() }
    val systemInstruction =
      activeSystemPrompt.takeIf { it.isNotBlank() }?.let {
        Contents.of(listOf(Content.Text(it)))
      }
    conversation =
      activeEngine.createConversation(
        ConversationConfig(
          samplerConfig =
            SamplerConfig(
              topK = settings.topK,
              topP = settings.topP,
              temperature = settings.temperature,
            ),
          systemInstruction = systemInstruction,
        )
      )
  }

  override suspend fun generate(
    prompt: String,
    imageBytes: List<ByteArray>,
    onChunk: (String) -> Unit,
  ): String =
    suspendCancellableCoroutine { continuation ->
      val activeConversation =
        conversation
          ?: run {
            continuation.resumeWithException(IllegalStateException("Import a model first."))
            return@suspendCancellableCoroutine
          }
      val response = StringBuilder()
      if (imageBytes.isNotEmpty() && activeSettings?.imageInputEnabled != true) {
        continuation.resumeWithException(
          IllegalStateException("Enable image input in Settings and reload the model.")
        )
        return@suspendCancellableCoroutine
      }
      require(imageBytes.size <= 1) { "Runner supports one image per turn." }
      val contents =
        buildList {
          imageBytes.forEach { add(Content.ImageBytes(it)) }
          add(Content.Text(prompt))
        }

      continuation.invokeOnCancellation { activeConversation.cancelProcess() }
      activeConversation.sendMessageAsync(
        Contents.of(contents),
        object : MessageCallback {
          override fun onMessage(message: Message) {
            if (!continuation.isActive) return
            val chunk = message.toString()
            response.append(chunk)
            onChunk(chunk)
          }

          override fun onDone() {
            if (continuation.isActive) continuation.resume(response.toString())
          }

          override fun onError(throwable: Throwable) {
            if (!continuation.isActive) return
            if (throwable is CancellationException) {
              continuation.cancel(throwable)
            } else {
              continuation.resumeWithException(throwable)
            }
          }
        },
        emptyMap(),
      )
    }

  override fun cancel() {
    conversation?.cancelProcess()
  }

  override fun close() {
    runCatching { conversation?.close() }
    runCatching { engine?.close() }
    conversation = null
    engine = null
    activeSettings = null
    activeSystemPrompt = ""
  }
}
