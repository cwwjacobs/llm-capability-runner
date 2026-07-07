package com.terminus.edge.light

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.terminus.edge.light.context.ContextBuildInput
import com.terminus.edge.light.context.ContextMode
import com.terminus.edge.light.context.ContextSettings
import com.terminus.edge.light.context.ContextSnapshot
import com.terminus.edge.light.context.ContextWindowManager
import com.terminus.edge.light.context.FixedContextEntry
import com.terminus.edge.light.context.ManagedContextMessage
import com.terminus.edge.light.context.RetentionPolicy
import com.terminus.edge.light.image.ImageAttachment
import com.terminus.edge.light.image.ImageAttachmentLoader
import com.terminus.edge.light.inference.GenerationSettings
import com.terminus.edge.light.inference.LiteRtSwarmEngine
import com.terminus.edge.light.inference.AgentRole
import com.terminus.edge.light.inference.ApiProviderConfiguration
import com.terminus.edge.light.inference.InferenceBackend
import com.terminus.edge.light.inference.RemoteApiClient
import com.terminus.edge.light.model.ModelDescriptor
import com.terminus.edge.light.model.DownloadedModel
import com.terminus.edge.light.model.ModelStore
import com.terminus.edge.light.model.StagedModel
import com.terminus.edge.light.ledger.ConversationEntity
import com.terminus.edge.light.ledger.ConversationLedger
import com.terminus.edge.light.skill.ContextPrompt
import com.terminus.edge.light.skill.SkillRecord
import com.terminus.edge.light.skill.SkillVault
import com.terminus.edge.light.persona.Persona
import com.terminus.edge.light.persona.PersonaVault
import com.terminus.edge.light.memory.MemoryRecord
import com.terminus.edge.light.memory.MemoryVault
import com.terminus.edge.light.trace.InferenceCompletedTrace
import com.terminus.edge.light.trace.ContextManagementTrace
import com.terminus.edge.light.trace.InferenceOutcomeTrace
import com.terminus.edge.light.trace.InferenceStartedTrace
import com.terminus.edge.light.trace.ReviewDecision
import com.terminus.edge.light.trace.ReviewRubric
import com.terminus.edge.light.trace.ReviewTrace
import com.terminus.edge.light.trace.TraceArtifactStore
import com.terminus.edge.light.trace.TraceIntegrity
import com.terminus.edge.light.trace.TraceLedger
import com.terminus.edge.light.spine.RuntimeSpine
import com.terminus.edge.light.spine.SpineReadResult
import com.terminus.edge.light.spine.SpineRecordType
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class MessageRole {
  USER,
  ASSISTANT,
  ERROR,
}

data class UiMessage(
  val id: String = UUID.randomUUID().toString(),
  val role: MessageRole,
  val content: String,
  val traceId: String? = null,
  val reviewDecision: ReviewDecision? = null,
  val image: ImageAttachment? = null,
  val retentionPolicy: RetentionPolicy =
    if (role == MessageRole.ERROR) RetentionPolicy.TEMPORARY else RetentionPolicy.COMPRESSIBLE,
)

private data class ConversationTurn(
  val turnIndex: Int,
  val effectivePrompt: String,
  val response: String,
)

enum class ExportMode {
  RAW,
  CURATED,
  REPLAY,
}

class EdgeController(private val context: Context) : AutoCloseable {
  private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val preferences = context.getSharedPreferences("edge_light_settings", Context.MODE_PRIVATE)
  private val masterKey = MasterKey.Builder(context)
      .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
      .build()
  private val securePreferences = EncryptedSharedPreferences.create(
      context,
      "edge_light_secure_prefs",
      masterKey,
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
  )
  private val modelStore = ModelStore(context)
  private val engine = LiteRtSwarmEngine(context)
  private val remoteApiClient = RemoteApiClient()
  val agentStates = engine.agentStates
  private val traceRoot = File(context.filesDir, "traces")
  private val traceArtifactStore = TraceArtifactStore(File(traceRoot, "artifacts"))
  private val traceLedger =
    TraceLedger(File(traceRoot, "trace_events.jsonl"), artifactStore = traceArtifactStore)
  private val conversationLedger = ConversationLedger(context)
  private val runtimeSpine =
    RuntimeSpine(
      file = File(context.filesDir, "runtime_spine/runtime-spine.jsonl"),
      legacyTraceFile = File(traceRoot, "trace_events.jsonl"),
    )
  private val skillVault = SkillVault(File(context.filesDir, "skills"))
  private var conversationTurns = emptyList<ConversationTurn>()
  private var nextTurnIndex = 0
  private var lastTraceId: String? = null
  private var conversationStateKnown = true
  private var compressedMessageIds by mutableStateOf<Set<String>>(emptySet())
  private var contextCompressionOperations by mutableStateOf<List<String>>(emptyList())

  var settings by mutableStateOf(loadGenerationSettings())
    private set
  var systemPrompt by
    mutableStateOf(
      preferences.getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
    )
    private set
  var hfToken by mutableStateOf(securePreferences.getString("hf_token", "") ?: "")
    private set
  var geminiApiKey by mutableStateOf(securePreferences.getString("gemini_api_key", "") ?: "")
    private set
  var deepSeekApiKey by
    mutableStateOf(securePreferences.getString("deepseek_api_key", "") ?: "")
    private set
  var inferenceBackend by
    mutableStateOf(
      InferenceBackend.fromWireValue(preferences.getString(KEY_INFERENCE_BACKEND, null))
    )
    private set
  var geminiModel by
    mutableStateOf(
      preferences.getString(KEY_GEMINI_MODEL, DEFAULT_GEMINI_MODEL) ?: DEFAULT_GEMINI_MODEL
    )
    private set
  var deepSeekModel by
    mutableStateOf(
      preferences.getString(KEY_DEEPSEEK_MODEL, DEFAULT_DEEPSEEK_MODEL)
        ?: DEFAULT_DEEPSEEK_MODEL
    )
    private set
  var themeMode by
    mutableStateOf(EdgeThemeMode.fromWireValue(preferences.getString(KEY_THEME_MODE, null)))
    private set
  var contextSettings by mutableStateOf(loadContextSettings())
    private set
  var sessionId by mutableStateOf(UUID.randomUUID().toString())
    private set

  var model by mutableStateOf<ModelDescriptor?>(modelStore.current())
    private set
  var messages by mutableStateOf<List<UiMessage>>(emptyList())
    private set
  var pendingImage by mutableStateOf<ImageAttachment?>(null)
    private set
  var scannedModels by mutableStateOf<List<ModelDescriptor>>(emptyList())
    private set

  var isBusy by mutableStateOf(false)
    private set

  var sessionIds by mutableStateOf<List<String>>(emptyList())
    private set
  var conversations by mutableStateOf<List<ConversationEntity>>(emptyList())
    private set
  var activeSessionId by mutableStateOf<String?>(null)
    private set
  var personas by mutableStateOf<List<Persona>>(emptyList())
    private set
  var activePersonaIds by mutableStateOf<Map<AgentRole, String?>>(emptyMap())
    private set
  var archiveSize by mutableStateOf(0L)
    private set
  var blobStoreSize by mutableStateOf(0L)
    private set
  var memories by mutableStateOf<List<MemoryRecord>>(emptyList())
    private set
  var selectedMemoryIds by mutableStateOf<Set<String>>(emptySet())
    private set

  var status by mutableStateOf("Import a GGUF or LiteRT-LM model to begin.")
    private set
  var traceEnabled by
    mutableStateOf(
      if (preferences.contains(KEY_TRACE_ENABLED)) {
        preferences.getBoolean(KEY_TRACE_ENABLED, true)
      } else {
        true
      }
    )
    private set
  var traceStats by mutableStateOf(traceLedger.stats())
    private set
  var skills by mutableStateOf<List<SkillRecord>>(emptyList())
    private set
  var selectedSkillIds by mutableStateOf<Set<String>>(emptySet())
    private set
  var spineReadResult by mutableStateOf(SpineReadResult(emptyList(), 0, 0, true))
    private set

  init {
    if (!preferences.getBoolean(KEY_SPINE_DEFAULT_APPLIED, false)) {
      traceEnabled = true
      preferences
        .edit()
        .putBoolean(KEY_TRACE_ENABLED, true)
        .putBoolean(KEY_SPINE_DEFAULT_APPLIED, true)
        .apply()
    }
    seedBundledSkills()
    refreshSkills()
    activeSessionId = sessionId
    controllerScope.launch {
      refreshConversations()
      refreshRuntimeSpine()
      appendSpine(
        type = SpineRecordType.CONTINUITY_LOG,
        payload =
          buildJsonObject {
            put("action", "session_opened")
            put("session_id", sessionId)
          },
      )
    }
  }

  suspend fun scanModels() {
    if (isBusy) return
    isBusy = true
    status = "Scanning storage for models..."
    try {
      val found = withContext(Dispatchers.IO) { modelStore.scanStorage() }
      scannedModels = found
      status = "Found ${found.size} models on device."
    } catch (e: Exception) {
      status = "Scan failed: ${e.message}"
    } finally {
      isBusy = false
    }
  }

  fun cancelScan() {
    scannedModels = emptyList()
    status = "Scan cancelled."
  }

  suspend fun selectScannedModel(file: File) {
    check(!isBusy) { "Wait for the current operation to finish." }
    isBusy = true
    status = "Loading ${file.name}..."
    val previous = model
    try {
      val descriptor = withContext(Dispatchers.IO) { modelStore.commitExternal(file, persist = false) }
      loadModel(descriptor)
      withContext(Dispatchers.IO) { modelStore.persistExternal(descriptor) }
      model = descriptor
      persistInferenceBackend(InferenceBackend.LOCAL)
      status = "Model loaded successfully."
      scannedModels = emptyList() // clear after picking
    } catch (error: Throwable) {
      status = error.message ?: "Failed to load the selected model."
      previous?.let { loadModel(it) }
    } finally {
      isBusy = false
    }
  }

  suspend fun archiveScannedModel(file: File) {
    check(!isBusy) { "Wait for the current operation to finish." }
    if (file.absolutePath == model?.path) {
      status = "Switch to another model before archiving the active model."
      return
    }
    isBusy = true
    status = "Archiving ${file.name}..."
    try {
      val archived = withContext(Dispatchers.IO) { modelStore.archive(file) }
      val found = withContext(Dispatchers.IO) { modelStore.scanStorage() }
      scannedModels = found
      status = "Model archived locally as ${archived.parentFile?.name}/${archived.name}."
    } catch (error: Throwable) {
      status = error.message ?: "Model could not be archived."
    } finally {
      isBusy = false
    }
  }

  suspend fun archiveStaleModels() {
    check(!isBusy) { "Wait for the current operation to finish." }
    val activePath = model?.path
    val stale =
      scannedModels
        .filter { !it.usable && it.path != activePath }
        .map { File(it.path) }
    if (stale.isEmpty()) {
      status = "No stale model candidates to archive."
      return
    }
    isBusy = true
    status = "Archiving ${stale.size} stale model${if (stale.size == 1) "" else "s"}..."
    try {
      val archivedCount =
        withContext(Dispatchers.IO) {
          stale.count { file ->
            runCatching {
                modelStore.archive(file)
              }
              .isSuccess
          }
        }
      scannedModels = withContext(Dispatchers.IO) { modelStore.scanStorage() }
      status = "Archived $archivedCount stale model${if (archivedCount == 1) "" else "s"} locally."
    } catch (error: Throwable) {
      status = error.message ?: "Stale models could not be archived."
    } finally {
      isBusy = false
    }
  }

  suspend fun selectDownloadedModel(downloaded: DownloadedModel) {
    check(!isBusy) { "Wait for the current operation to finish." }
    isBusy = true
    status = "Validating ${downloaded.modelFile.name}..."
    val previous = model
    try {
      val descriptor =
        withContext(Dispatchers.IO) {
          modelStore.commitExternal(
            file = downloaded.modelFile,
            projector = downloaded.projectorFile,
            sourceRepository = downloaded.repositoryId,
            sourceRevision = downloaded.revision,
            persist = false,
          )
        }
      loadModel(descriptor)
      withContext(Dispatchers.IO) { modelStore.persistExternal(descriptor) }
      model = descriptor
      persistInferenceBackend(InferenceBackend.LOCAL)
      status = "${descriptor.displayName} loaded."
    } catch (error: Throwable) {
      status = error.message ?: "Downloaded model could not be loaded."
      previous?.let { runCatching { loadModel(it) } }
    } finally {
      isBusy = false
    }
  }

  suspend fun restoreModel() {
    if (inferenceBackend != InferenceBackend.LOCAL) {
      status = "${inferenceBackend.label} ready."
      return
    }
    val current = model ?: return
    isBusy = true
    try {
      loadModel(current)
    } catch (error: Throwable) {
      status = error.message ?: "Saved model could not be loaded."
      messages = messages + UiMessage(role = MessageRole.ERROR, content = status)
    } finally {
      isBusy = false
    }
  }

  suspend fun importModel(uri: Uri) {
    check(!isBusy) { "Wait for the current response to finish." }
    isBusy = true
    status = "Importing model..."
    val previous = model
    var staged: StagedModel? = null
    try {
      val candidate = withContext(Dispatchers.IO) { modelStore.stage(uri) }
      staged = candidate
      status = "Validating ${candidate.descriptor.displayName}..."
      loadModel(candidate.descriptor)
      val imported = withContext(Dispatchers.IO) { modelStore.commit(candidate) }
      model = imported
      persistInferenceBackend(InferenceBackend.LOCAL)
      resetConversationTracking()
      pendingImage = null
      status = "Ready."
    } catch (error: Throwable) {
      staged?.let { candidate -> withContext(Dispatchers.IO) { modelStore.discard(candidate) } }
      val restoreError =
        previous?.let { descriptor -> runCatching { loadModel(descriptor) }.exceptionOrNull() }
      status =
        when {
          restoreError != null ->
            "${error.message ?: "Model import failed."} Previous model restore also failed: " +
              "${restoreError.message ?: "unknown error"}"
          previous != null -> "${error.message ?: "Model import failed."} Previous model restored."
          else -> error.message ?: "Model import failed."
        }
      messages = messages + UiMessage(role = MessageRole.ERROR, content = status)
    } finally {
      isBusy = false
    }
  }

  suspend fun attachImage(uri: Uri) {
    if (isBusy) return
    if (!imageInputAvailable) {
      status = "The selected inference backend does not expose image input."
      return
    }
    isBusy = true
    status = "Preparing image..."
    try {
      pendingImage = withContext(Dispatchers.IO) { ImageAttachmentLoader.load(context, uri) }
      val image = requireNotNull(pendingImage)
      status = "Image ready: ${image.displayName} (${image.width}x${image.height})."
    } catch (error: Throwable) {
      status = error.message ?: "Image import failed."
    } finally {
      isBusy = false
    }
  }

  fun removePendingImage() {
    pendingImage = null
    status = "Image removed."
  }

  fun send(prompt: String): Boolean {
    val activeModel = model
    val activeBackend = inferenceBackend
    if (!inferenceReady) return false
    if (activeBackend == InferenceBackend.LOCAL && activeModel == null) return false
    val activeImage = pendingImage
    if (isBusy || (prompt.isBlank() && activeImage == null)) return false
    if (activeImage != null && !imageInputAvailable) {
      status = "The selected inference backend does not support this image attachment."
      return false
    }

    val userMessage =
      UiMessage(
        role = MessageRole.USER,
        content = prompt.trim().ifEmpty { "Describe this image." },
        image = activeImage,
      )
    val attachedSkills =
      skills.filter { it.id in selectedSkillIds }.sortedBy { it.name.lowercase() }
    var contextSnapshot =
      buildContextSnapshot(
        draftPrompt = userMessage.content,
        attachedSkills = attachedSkills,
      )
    if (
      contextSettings.mode == ContextMode.AUTOMATIC &&
        (contextSnapshot.shouldRecommendCompression || contextSnapshot.usage.exceedsInputLimit)
    ) {
      contextSnapshot =
        compressContextSnapshot(
          draftPrompt = userMessage.content,
          attachedSkills = attachedSkills,
          forceOne = false,
        )
      compressedMessageIds = compressedMessageIds + contextSnapshot.newlyCompressedIds
      contextCompressionOperations = contextSnapshot.compressionOperations
    }
    var droppedCount = 0
    while (contextSnapshot.usage.exceedsInputLimit) {
      val firstDropCandidateIndex = messages.indexOfFirst { 
        it.role != MessageRole.ERROR && it.retentionPolicy != RetentionPolicy.PINNED 
      }
      if (firstDropCandidateIndex == -1) break
      messages = messages.filterIndexed { index, _ -> index != firstDropCandidateIndex }
      droppedCount++
      contextSnapshot = buildContextSnapshot(userMessage.content, attachedSkills)
      if (contextSettings.mode == ContextMode.AUTOMATIC) {
        contextSnapshot = compressContextSnapshot(userMessage.content, attachedSkills, forceOne = false)
      }
    }

    if (contextSnapshot.usage.exceedsInputLimit) {
      status =
        "Context limit exceeded, unable to truncate pinned messages."
      return false
    }
    val effectivePrompt = contextSnapshot.effectivePrompt
    val assistantId = UUID.randomUUID().toString()
    val turnIndex = nextTurnIndex
    nextTurnIndex += 1
    val parentTraceId = lastTraceId
    val priorTurns = conversationTurns
    messages =
      messages +
        userMessage +
        UiMessage(id = assistantId, role = MessageRole.ASSISTANT, content = "")
    controllerScope.launch { persistConversationMessage(userMessage) }
    pendingImage = null
    isBusy = true
    status =
      when {
        droppedCount > 0 -> "Truncated $droppedCount messages. Generating..."
        contextSnapshot.newlyCompressedIds.isEmpty() -> "Generating..."
        else -> "Compressed ${contextSnapshot.newlyCompressedIds.size} context entries. Generating..."
      }
    val recordThisResponse =
      traceEnabled && activeBackend == InferenceBackend.LOCAL && activeModel != null
    val spineTraceId = UUID.randomUUID().toString()

    val serviceIntent = Intent(context, RunnerForegroundService::class.java)
    context.startForegroundService(serviceIntent)
    
    controllerScope.launch {
      val startedAt = SystemClock.elapsedRealtime()
      val firstChunkAt = AtomicLong(-1L)
      val chunkCount = AtomicInteger(0)
      val partialResponse = StringBuilder()
      var activeTrace: InferenceStartedTrace? = null
      var traceError: Throwable? = null
      try {
        if (activeBackend == InferenceBackend.LOCAL) {
          withContext(Dispatchers.IO) { engine.resetConversation(AgentRole.ORCHESTRATOR) }
        }
        appendSpine(
          type = SpineRecordType.TRACE,
          traceId = spineTraceId,
          payload =
            buildJsonObject {
              put("phase", "started")
              put("turn_index", turnIndex)
              put("user_prompt", userMessage.content)
              put("effective_prompt", effectivePrompt)
              put("has_image", activeImage != null)
              put("backend", activeBackend.wireValue)
              put("model", activeBackendModelLabel())
            },
        )
        if (recordThisResponse) {
          val traceModel = requireNotNull(activeModel)
          status = "Preparing trace snapshot..."
          runCatching {
              withContext(Dispatchers.IO) {
                createStartedTrace(
                  activeModel = traceModel,
                  turnIndex = turnIndex,
                  parentTraceId = parentTraceId,
                  userPrompt = userMessage.content,
                  effectivePrompt = effectivePrompt,
                  priorTurns = priorTurns,
                  attachedSkills = attachedSkills,
                  contextSnapshot = contextSnapshot,
                  image = activeImage,
                ).also(traceLedger::appendInferenceStarted)
              }
            }
            .onSuccess { trace ->
              activeTrace = trace
              lastTraceId = trace.traceId
              updateMessage(assistantId) { it.copy(traceId = trace.traceId) }
              refreshTraceStats()
            }
            .onFailure { error -> traceError = error }
          status = "Generating..."
        }
        val onChunk: (String) -> Unit = { chunk ->
            chunkCount.incrementAndGet()
            firstChunkAt.compareAndSet(-1L, SystemClock.elapsedRealtime())
            synchronized(partialResponse) { partialResponse.append(chunk) }
            controllerScope.launch(Dispatchers.Main.immediate) {
              updateMessage(assistantId) { it.copy(content = it.content + chunk) }
            }
          }
        val response =
          when (activeBackend) {
            InferenceBackend.LOCAL ->
              engine.generate(
                role = AgentRole.ORCHESTRATOR,
                prompt = effectivePrompt,
                imageBytes = listOfNotNull(activeImage?.pngBytes),
                onChunk = onChunk,
              )
            InferenceBackend.GEMINI,
            InferenceBackend.DEEPSEEK ->
              remoteApiClient.generate(
                backend = activeBackend,
                apiKey = activeApiKey(),
                model = activeBackendModelLabel(),
                systemPrompt = systemPrompt,
                prompt = effectivePrompt,
                imageBytes = listOfNotNull(activeImage?.pngBytes),
                settings = settings,
                onChunk = onChunk,
              )
          }
        val latencyMs = SystemClock.elapsedRealtime() - startedAt
        activeTrace?.let { trace ->
          runCatching {
              withContext(Dispatchers.IO) {
                traceLedger.appendInferenceCompleted(
                  InferenceCompletedTrace(
                    traceId = trace.traceId,
                    sessionId = trace.sessionId,
                    createdAtMs = System.currentTimeMillis(),
                    response = response,
                    latencyMs = latencyMs,
                    timeToFirstChunkMs = elapsedFrom(startedAt, firstChunkAt.get()),
                    chunkCount = chunkCount.get(),
                  )
                )
              }
            }
            .onFailure { error -> traceError = error }
        }
        conversationTurns =
          conversationTurns +
            ConversationTurn(
              turnIndex = turnIndex,
              effectivePrompt = effectivePrompt,
              response = response,
            )
        val completedMessage =
          messages.firstOrNull { it.id == assistantId }
            ?.copy(traceId = activeTrace?.traceId ?: spineTraceId)
        if (completedMessage != null) {
          updateMessage(assistantId) { completedMessage }
          persistConversationMessage(completedMessage)
        }
        appendSpine(
          type = SpineRecordType.TRACE,
          traceId = spineTraceId,
          payload =
            buildJsonObject {
              put("phase", "completed")
              put("response", response)
              put("latency_ms", latencyMs)
              put("chunk_count", chunkCount.get())
              put("backend", activeBackend.wireValue)
              put("model", activeBackendModelLabel())
            },
        )
        refreshTraceStats()
        status =
          when {
            traceError != null -> "Response complete. Runtime Spine issue: ${traceError?.message ?: "unknown error"}"
            activeTrace != null -> "Response complete."
            else -> "Response complete."
          }
      } catch (_: CancellationException) {
        conversationStateKnown = false
        val latencyMs = SystemClock.elapsedRealtime() - startedAt
        val partial = synchronized(partialResponse) { partialResponse.toString() }
        activeTrace?.let { trace ->
          runCatching {
              withContext(NonCancellable + Dispatchers.IO) {
                traceLedger.appendInferenceCancelled(
                  outcomeTrace(
                    trace = trace,
                    latencyMs = latencyMs,
                    firstChunkAt = firstChunkAt.get(),
                    startedAt = startedAt,
                    chunkCount = chunkCount.get(),
                    partialResponse = partial,
                    category = "operator_cancelled",
                    message = null,
                  )
                )
                traceLedger.stats()
              }
            }
            .onSuccess { stats -> traceStats = stats }
            .onFailure { error -> traceError = error }
        }
        status =
          when {
            traceError != null -> "Generation stopped. Trace capture issue: ${traceError?.message}"
            else -> "Generation stopped."
          }
        appendSpine(
          type = SpineRecordType.FAILURE_TRACE,
          traceId = spineTraceId,
          payload =
            buildJsonObject {
              put("category", "operator_cancelled")
              put("partial_response", partial)
            },
        )
      } catch (error: Throwable) {
        conversationStateKnown = false
        updateMessage(assistantId) {
          it.copy(role = MessageRole.ERROR, content = error.message ?: "Generation failed.")
        }
        messages.firstOrNull { it.id == assistantId }?.let { persistConversationMessage(it) }
        val latencyMs = SystemClock.elapsedRealtime() - startedAt
        val partial = synchronized(partialResponse) { partialResponse.toString() }
        activeTrace?.let { trace ->
          runCatching {
              withContext(Dispatchers.IO) {
                traceLedger.appendInferenceFailed(
                  outcomeTrace(
                    trace = trace,
                    latencyMs = latencyMs,
                    firstChunkAt = firstChunkAt.get(),
                    startedAt = startedAt,
                    chunkCount = chunkCount.get(),
                    partialResponse = partial,
                    category = error::class.java.simpleName.ifBlank { "generation_error" },
                    message = error.message,
                  )
                )
              }
            }
            .onFailure { traceFailure -> traceError = traceFailure }
        }
        refreshTraceStats()
        status =
          when {
            traceError != null ->
              "${error.message ?: "Generation failed."} Trace capture issue: ${traceError?.message}"
            else -> error.message ?: "Generation failed."
          }
        appendSpine(
          type = SpineRecordType.FAILURE_TRACE,
          traceId = spineTraceId,
          payload =
            buildJsonObject {
              put("category", error::class.java.simpleName.ifBlank { "generation_error" })
              put("message", error.message ?: "Generation failed.")
              put("partial_response", partial)
            },
        )
      } finally {
        context.startService(Intent(context, RunnerForegroundService::class.java).apply { action = "STOP" })
        isBusy = false
      }
    }
    return true
  }

  fun cancel() {
    engine.cancel()
    remoteApiClient.cancel()
  }

  fun updateTraceEnabled(enabled: Boolean, scope: CoroutineScope) {
    if (isBusy) {
      status = "Wait for the current operation to finish."
      return
    }
    traceEnabled = enabled
    preferences.edit().putBoolean(KEY_TRACE_ENABLED, enabled).apply()
    status = if (enabled) "Runtime Spine capture enabled." else "Runtime Spine capture paused."
    scope.launch {
      runCatching {
          withContext(Dispatchers.IO) {
            traceLedger.appendConsent(
              enabled = enabled,
              sessionId = sessionId,
              createdAtMs = System.currentTimeMillis(),
              appVersion = BuildConfig.VERSION_NAME,
            )
          }
        }
        .onSuccess { refreshTraceStats() }
        .onFailure { status = "Runtime Spine preference saved, but the legacy trace marker failed: ${it.message}" }
    }
  }

  fun updateThemeMode(mode: EdgeThemeMode) {
    themeMode = mode
    preferences.edit().putString(KEY_THEME_MODE, mode.wireValue).apply()
  }

  fun contextSnapshot(draftPrompt: String = ""): ContextSnapshot {
    val attachedSkills =
      skills.filter { it.id in selectedSkillIds }.sortedBy { it.name.lowercase() }
    return buildContextSnapshot(draftPrompt.trim(), attachedSkills)
  }

  val imageInputAvailable: Boolean
    get() =
      when (inferenceBackend) {
        InferenceBackend.LOCAL -> model != null && engine.capabilities().vision
        InferenceBackend.GEMINI -> geminiApiKey.isNotBlank()
        InferenceBackend.DEEPSEEK -> false
      }

  val inferenceReady: Boolean
    get() =
      when (inferenceBackend) {
        InferenceBackend.LOCAL -> model != null
        InferenceBackend.GEMINI -> geminiApiKey.isNotBlank()
        InferenceBackend.DEEPSEEK -> deepSeekApiKey.isNotBlank()
      }

  val apiConfiguration: ApiProviderConfiguration
    get() =
      ApiProviderConfiguration(
        backend = inferenceBackend,
        geminiModel = geminiModel,
        deepSeekModel = deepSeekModel,
      )

  val activeInferenceLabel: String
    get() =
      when (inferenceBackend) {
        InferenceBackend.LOCAL -> model?.displayName ?: "No on-device model selected"
        InferenceBackend.GEMINI -> "Gemini API · $geminiModel"
        InferenceBackend.DEEPSEEK -> "DeepSeek API · $deepSeekModel"
      }

  fun updateContextSettings(nextSettings: ContextSettings) {
    contextSettings = nextSettings.normalized(settings.maxTokens)
    preferences
      .edit()
      .putString(KEY_CONTEXT_MODE, contextSettings.mode.wireValue)
      .putInt(KEY_CONTEXT_THRESHOLD, contextSettings.compressionThresholdPercent)
      .putInt(KEY_CONTEXT_RESERVE, contextSettings.reservedOutputTokens)
      .apply()
    status = "Context management settings saved."
  }

  fun updateMessageRetention(messageId: String, policy: RetentionPolicy) {
    val message = messages.firstOrNull { it.id == messageId } ?: return
    if (message.role == MessageRole.ERROR && policy == RetentionPolicy.PINNED) {
      status = "Error entries cannot be pinned."
      return
    }
    updateMessage(messageId) { it.copy(retentionPolicy = policy) }
    if (
      policy == RetentionPolicy.PINNED ||
        policy == RetentionPolicy.SAFE_RETENTION ||
        policy == RetentionPolicy.EXCLUDED
    ) {
      compressedMessageIds = compressedMessageIds - messageId
      contextCompressionOperations =
        contextCompressionOperations.filterNot { it.contains(messageId) }
    }
    status = "${message.role.contextLabel()} marked ${policy.label.lowercase()}."
  }

  fun compressContext(draftPrompt: String = "") {
    if (isBusy) return
    val attachedSkills =
      skills.filter { it.id in selectedSkillIds }.sortedBy { it.name.lowercase() }
    val snapshot =
      compressContextSnapshot(
        draftPrompt = draftPrompt.trim(),
        attachedSkills = attachedSkills,
        forceOne = true,
      )
    compressedMessageIds = compressedMessageIds + snapshot.newlyCompressedIds
    contextCompressionOperations = snapshot.compressionOperations
    status =
      if (snapshot.newlyCompressedIds.isEmpty()) {
        "No eligible context entries to compress."
      } else {
        "Compressed ${snapshot.newlyCompressedIds.size} context entries. Originals are retained."
      }
  }

  fun restoreCompressedContext() {
    if (compressedMessageIds.isEmpty()) {
      status = "No compressed entries to restore."
      return
    }
    compressedMessageIds = emptySet()
    contextCompressionOperations = emptyList()
    status = "Original context entries restored."
  }

  fun clearTemporaryContext() {
    val temporaryIds =
      messages
        .filter { it.retentionPolicy == RetentionPolicy.TEMPORARY }
        .map(UiMessage::id)
        .toSet()
    if (temporaryIds.isEmpty()) {
      status = "No temporary context entries."
      return
    }
    messages =
      messages.map { message ->
        if (message.id in temporaryIds) {
          message.copy(retentionPolicy = RetentionPolicy.EXCLUDED)
        } else {
          message
        }
      }
    compressedMessageIds = compressedMessageIds - temporaryIds
    contextCompressionOperations =
      contextCompressionOperations.filterNot { operation ->
        temporaryIds.any(operation::contains)
      }
    status = "Excluded ${temporaryIds.size} temporary context entries."
  }

  fun newConversation() {
    if (isBusy) return
    messages = emptyList()
    pendingImage = null
    resetConversationTracking()
    activeSessionId = sessionId
    status = "New local conversation."
    controllerScope.launch {
      appendSpine(
        type = SpineRecordType.CONTINUITY_LOG,
        payload = buildJsonObject { put("action", "conversation_created") },
      )
      refreshConversations()
    }
  }

  fun updateModelSettings(
    nextSettings: GenerationSettings,
    nextSystemPrompt: String,
    scope: CoroutineScope,
  ) {
    if (isBusy) return
    val nextContextSettings = contextSettings.normalized(nextSettings.maxTokens)
    val current = model
    if (current == null) {
      applyModelSettingsState(nextSettings, nextSystemPrompt, nextContextSettings)
      status = "Model settings saved."
      return
    }
    val previousSettings = settings
    val previousSystemPrompt = systemPrompt
    scope.launch {
      isBusy = true
      status = "Applying model settings..."
      try {
        withContext(Dispatchers.IO) {
          engine.load(
            role = AgentRole.ORCHESTRATOR,
            model = current,
            systemPrompt = nextSystemPrompt,
            settings = nextSettings,
          )
        }
        applyModelSettingsState(nextSettings, nextSystemPrompt, nextContextSettings)
        messages = emptyList()
        pendingImage = null
        resetConversationTracking()
        status = "Model settings applied. Conversation reset."
      } catch (error: Throwable) {
        val restoreError =
          runCatching {
              withContext(Dispatchers.IO) {
                engine.load(
                  role = AgentRole.ORCHESTRATOR,
                  model = current,
                  systemPrompt = systemPrompt,
                  settings = settings,
                )
              }
            }
            .exceptionOrNull()
        status =
          if (restoreError == null) {
            "${error.message ?: "Could not apply model settings."} Previous settings restored."
          } else {
            "${error.message ?: "Could not apply model settings."} Restore also failed: " +
              "${restoreError.message ?: "unknown error"}"
          }
      } finally {
        isBusy = false
      }
    }
  }

  fun review(
    messageId: String,
    decision: ReviewDecision,
    correctedResponse: String?,
    note: String?,
    tags: List<String>,
    rubric: ReviewRubric,
    scope: CoroutineScope,
  ) {
    if (isBusy) return
    val message = messages.firstOrNull { it.id == messageId } ?: return
    val traceId = message.traceId ?: return
    scope.launch {
      try {
        withContext(Dispatchers.IO) {
          traceLedger.appendReview(
            ReviewTrace(
              traceId = traceId,
              createdAtMs = System.currentTimeMillis(),
              decision = decision,
              correctedResponse = correctedResponse,
              note = note,
              tags = tags,
              rubric = rubric,
              originalResponseSha256 = TraceIntegrity.sha256(message.content),
            )
          )
        }
        updateMessage(messageId) { it.copy(reviewDecision = decision) }
        val reviewed = messages.firstOrNull { it.id == messageId }
        if (reviewed != null) persistConversationMessage(reviewed)
        appendSpine(
          type = SpineRecordType.CORRECTION_TRACE,
          traceId = traceId,
          payload =
            buildJsonObject {
              put("decision", decision.wireValue)
              correctedResponse?.let { put("corrected_response", it) }
              note?.let { put("note", it) }
              put(
                "tags",
                buildJsonArray {
                  tags.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                },
              )
            },
        )
        refreshTraceStats()
        status = "Review saved: ${decision.wireValue}."
      } catch (error: Throwable) {
        status = error.message ?: "Could not save review."
      }
    }
  }

  fun export(uri: Uri, mode: ExportMode, scope: CoroutineScope) {
    if (isBusy) {
      status = "Wait for the current operation to finish."
      return
    }
    isBusy = true
    status = if (mode == ExportMode.REPLAY) "Building replay bundle..." else "Exporting traces..."
    scope.launch {
      try {
        val result =
          withContext(Dispatchers.IO) {
            val writeMode = if (mode == ExportMode.REPLAY) "w" else "wt"
            context.contentResolver.openOutputStream(uri, writeMode).use { output ->
              requireNotNull(output) { "Unable to open export destination." }
              when (mode) {
                ExportMode.RAW -> {
                  output.write(runtimeSpine.exportBytes())
                  "Exported ${runtimeSpine.read().records.size} Runtime Spine records."
                }
                ExportMode.CURATED ->
                  "Exported ${traceLedger.exportCurated(output)} curated training candidates."
                ExportMode.REPLAY -> {
                  model?.let { activeModel ->
                    if (!traceLedger.hasModelSnapshot(activeModel.sha256)) {
                      traceLedger.appendModelSnapshot(
                        snapshot = traceArtifactStore.snapshotModel(activeModel),
                        sessionId = sessionId,
                        createdAtMs = System.currentTimeMillis(),
                        appVersion = BuildConfig.VERSION_NAME,
                      )
                    }
                  }
                  val replay =
                    traceLedger.exportReplay(
                      output,
                      extraFiles =
                        mapOf(
                          "runtime-spine/runtime-spine.jsonl" to runtimeSpine.exportBytes(),
                          "runtime-spine/schema.txt" to
                            "runtime-spine.v1\n".toByteArray(Charsets.UTF_8),
                        ),
                      extraArtifacts =
                        model
                          ?.visionProjectorPath
                          ?.let(::File)
                          ?.takeIf(File::isFile)
                          ?.let { projector ->
                            mapOf(
                              "artifacts/projectors/${projector.name}" to
                                (projector to model?.visionProjectorSha256)
                            )
                          }
                          .orEmpty(),
                    )
                  "Replay bundle: ${replay.traceCount} traces, ${replay.artifactCount} artifacts, ${replay.modelCount} model snapshot${if (replay.modelCount == 1) "" else "s"}."
                }
              }
            }
          }
        if (mode == ExportMode.CURATED) {
          appendSpine(
            type = SpineRecordType.TRAINING_TRACE,
            payload =
              buildJsonObject {
                put("action", "curated_dataset_exported")
                put("label", "training_candidate_handoff")
              },
          )
        }
        if (mode == ExportMode.REPLAY) {
          appendSpine(
            type = SpineRecordType.PROVENANCE,
            payload = buildJsonObject { put("action", "replay_pack_exported") },
          )
        }
        status = result
      } catch (error: Throwable) {
        status = error.message ?: "Export failed."
      } finally {
        isBusy = false
      }
    }
  }

  fun archiveTraces(scope: CoroutineScope) {
    if (isBusy) {
      status = "Wait for the current operation to finish."
      return
    }
    isBusy = true
    scope.launch {
      try {
        val archiveName =
          withContext(Dispatchers.IO) {
            val archive = File(traceRoot, "archive/${System.currentTimeMillis()}").apply { mkdirs() }
            val events = File(traceRoot, "trace_events.jsonl")
            val artifacts = File(traceRoot, "artifacts")
            if (events.exists()) {
              java.nio.file.Files.move(events.toPath(), File(archive, events.name).toPath())
            }
            if (artifacts.exists()) {
              java.nio.file.Files.move(artifacts.toPath(), File(archive, artifacts.name).toPath())
            }
            runtimeSpine.archive()
            archive.name
          }
        refreshTraceStats()
        refreshRuntimeSpine()
        lastTraceId = null
        status = "Runtime records archived locally in $archiveName."
      } finally {
        isBusy = false
      }
    }
  }

  private fun seedBundledSkills() {
    if (preferences.getBoolean(KEY_SKILLS_SEEDED, false)) return
    runCatching {
        context.assets
          .list("skills")
          .orEmpty()
          .forEach { directory ->
            val path = "skills/$directory/SKILL.md"
            val source =
              context.assets.open(path).use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
              }
            skillVault.seedMarkdown(source)
          }
      }
      .onSuccess { preferences.edit().putBoolean(KEY_SKILLS_SEEDED, true).apply() }
  }

  fun importSkills(uris: List<Uri>, scope: CoroutineScope) {
    if (uris.isEmpty() || isBusy) return
    scope.launch {
      try {
        val imported =
          withContext(Dispatchers.IO) {
            uris.map { uri ->
              val text =
                context.contentResolver.openInputStream(uri).use { input ->
                  requireNotNull(input) { "Unable to open selected Skill." }
                  input.bufferedReader(Charsets.UTF_8).readText()
                }
              skillVault.importMarkdown(text)
            }
          }
        refreshSkills()
        status = "Imported ${imported.size} Skill${if (imported.size == 1) "" else "s"}."
      } catch (error: Throwable) {
        status = error.message ?: "Skill import failed."
      }
    }
  }

  fun addSkill(name: String, description: String, instructions: String): Boolean =
    try {
      skillVault.add(name, description, instructions)
      refreshSkills()
      status = "Saved Skill \"${name.trim()}\"."
      true
    } catch (error: Throwable) {
      status = error.message ?: "Could not add Skill."
      false
    }

  fun toggleSkill(id: String) {
    if (skills.none { it.id == id }) return
    selectedSkillIds =
      if (id in selectedSkillIds) selectedSkillIds - id else selectedSkillIds + id
    preferences.edit().putStringSet(KEY_SELECTED_SKILLS, selectedSkillIds).apply()
    status =
      if (selectedSkillIds.isEmpty()) {
        "No Skills attached."
      } else {
        "${selectedSkillIds.size} Skill${if (selectedSkillIds.size == 1) "" else "s"} attached."
      }
  }

  private fun refreshSkills() {
    skills = skillVault.list()
    val knownIds = skills.map(SkillRecord::id).toSet()
    val saved = preferences.getStringSet(KEY_SELECTED_SKILLS, emptySet()).orEmpty()
    selectedSkillIds = (selectedSkillIds + saved).intersect(knownIds)
    preferences.edit().putStringSet(KEY_SELECTED_SKILLS, selectedSkillIds).apply()
  }

  private fun createStartedTrace(
    activeModel: ModelDescriptor,
    turnIndex: Int,
    parentTraceId: String?,
    userPrompt: String,
    effectivePrompt: String,
    priorTurns: List<ConversationTurn>,
    attachedSkills: List<SkillRecord>,
    contextSnapshot: ContextSnapshot,
    image: ImageAttachment?,
  ): InferenceStartedTrace {
    val skillArtifacts =
      attachedSkills.map { record ->
        traceArtifactStore.snapshotText(
          kind = "skills",
          logicalId = record.id,
          extension = "md",
          mediaType = "text/markdown",
          content = record.source,
        )
      }
    val historyArtifact =
      priorTurns.takeIf(List<ConversationTurn>::isNotEmpty)?.let { turns ->
        val history =
          buildJsonObject {
            put("schema_version", "edge-conversation-history.v1")
            put("session_id", sessionId)
            put(
              "turns",
              buildJsonArray {
                turns.forEach { turn ->
                  add(
                    buildJsonObject {
                      put("turn_index", turn.turnIndex)
                      put("effective_prompt", turn.effectivePrompt)
                      put("effective_prompt_sha256", TraceIntegrity.sha256(turn.effectivePrompt))
                      put("response", turn.response)
                      put("response_sha256", TraceIntegrity.sha256(turn.response))
                    }
                  )
                }
              },
            )
          }
        traceArtifactStore.snapshotText(
          kind = "conversations",
          logicalId = "$sessionId-$turnIndex",
          extension = "json",
          mediaType = "application/json",
          content = history.toString(),
        )
      }
    val imageArtifacts =
      image?.let { attachment ->
        listOf(
          traceArtifactStore.snapshotBytes(
            kind = "images",
            logicalId = attachment.displayName,
            extension = "png",
            mediaType = "image/png",
            bytes = attachment.pngBytes,
          )
        )
      }.orEmpty()
    return InferenceStartedTrace(
      sessionId = sessionId,
      createdAtMs = System.currentTimeMillis(),
      turnIndex = turnIndex,
      parentTraceId = parentTraceId,
      historyState =
        if (conversationStateKnown) {
          "complete_application_history"
        } else {
          "uncertain_after_failed_or_cancelled_turn"
        },
      userPrompt = userPrompt,
      effectivePrompt = effectivePrompt,
      systemPrompt = systemPrompt,
      historyArtifact = historyArtifact,
      model = traceArtifactStore.describeModel(activeModel),
      maxTokens = settings.maxTokens,
      topK = settings.topK,
      topP = settings.topP,
      temperature = settings.temperature,
      imageInputEnabled = settings.imageInputEnabled,
      appVersion = BuildConfig.VERSION_NAME,
      runtimeName = engine.metadata()?.runtimeName ?: activeModel.runtimeType.name.lowercase(),
      runtimeVersion = engine.metadata()?.runtimeVersion ?: "unknown",
      backend = "cpu",
      skillArtifacts = skillArtifacts,
      imageArtifacts = imageArtifacts,
      contextManagement =
        ContextManagementTrace(
          totalTokens = contextSnapshot.usage.totalTokens,
          estimatedInputTokens = contextSnapshot.usage.estimatedTokens,
          inputCharacters = contextSnapshot.usage.characters,
          reservedOutputTokens = contextSnapshot.usage.reservedOutputTokens,
          estimateMethod = "characters_divided_by_4",
          mode = contextSnapshot.settings.mode.wireValue,
          compressionThresholdPercent =
            contextSnapshot.settings.compressionThresholdPercent,
          includedEntryIds = contextSnapshot.includedEntryIds,
          excludedEntryIds = contextSnapshot.excludedEntryIds,
          compressedEntryIds = contextSnapshot.compressedEntryIds,
          retainedEntryIds = contextSnapshot.retainedEntryIds,
          compressionOperations = contextSnapshot.compressionOperations,
        ),
    )
  }

  private fun outcomeTrace(
    trace: InferenceStartedTrace,
    latencyMs: Long,
    firstChunkAt: Long,
    startedAt: Long,
    chunkCount: Int,
    partialResponse: String,
    category: String,
    message: String?,
  ): InferenceOutcomeTrace =
    InferenceOutcomeTrace(
      traceId = trace.traceId,
      sessionId = trace.sessionId,
      createdAtMs = System.currentTimeMillis(),
      latencyMs = latencyMs,
      timeToFirstChunkMs = elapsedFrom(startedAt, firstChunkAt),
      chunkCount = chunkCount,
      partialResponse = partialResponse,
      category = category,
      message = message,
    )

  private fun elapsedFrom(startedAt: Long, eventAt: Long): Long? =
    eventAt.takeIf { it >= startedAt }?.minus(startedAt)

  private suspend fun refreshTraceStats() {
    traceStats = withContext(Dispatchers.IO) { traceLedger.stats() }
  }

  private suspend fun persistConversationMessage(message: UiMessage) {
    withContext(Dispatchers.IO) {
      conversationLedger.saveMessage(message, sessionId)
      conversationLedger.upsertConversation(
        sessionId = sessionId,
        messages = messages,
        runtimeType = inferenceBackend.name,
        modelName = activeBackendModelLabel(),
      )
    }
    appendSpine(
      type = SpineRecordType.CONTINUITY_LOG,
      traceId = message.traceId,
      payload =
        buildJsonObject {
          put("action", "message_saved")
          put("message_id", message.id)
          put("role", message.role.name.lowercase())
          put("content", message.content)
          put("has_image", message.image != null)
          message.image?.let {
            put("image_sha256", it.sha256)
            put("image_name", it.displayName)
          }
        },
    )
    refreshConversations()
  }

  private suspend fun refreshConversations() {
    conversations = withContext(Dispatchers.IO) { conversationLedger.getConversations() }
    sessionIds = conversations.map(ConversationEntity::id)
  }

  suspend fun refreshRuntimeSpine() {
    spineReadResult = withContext(Dispatchers.IO) { runtimeSpine.read() }
  }

  private suspend fun appendSpine(
    type: SpineRecordType,
    payload: kotlinx.serialization.json.JsonObject,
    traceId: String? = null,
  ) {
    if (!traceEnabled) return
    val activeModel = model
    withContext(Dispatchers.IO) {
      runtimeSpine.append(
        type = type,
        sessionId = sessionId,
        traceId = traceId,
        payload = payload,
        provenance =
          buildJsonObject {
            put("app_version", BuildConfig.VERSION_NAME)
            put("runtime", inferenceBackend.wireValue)
            put("model_name", activeBackendModelLabel())
            if (inferenceBackend == InferenceBackend.LOCAL) {
              engine.metadata()?.runtimeVersion?.let { put("runtime_version", it) }
              activeModel?.let {
                put("model_sha256", it.sha256)
                put("model_type", it.runtimeType.name.lowercase())
              }
            }
          },
      )
    }
    spineReadResult = withContext(Dispatchers.IO) { runtimeSpine.read() }
  }

  private fun resetConversationTracking() {
    conversationTurns = emptyList()
    compressedMessageIds = emptySet()
    contextCompressionOperations = emptyList()
    nextTurnIndex = 0
    lastTraceId = null
    conversationStateKnown = true
    sessionId = UUID.randomUUID().toString()
  }

  private suspend fun loadModel(descriptor: ModelDescriptor) {
    status = "Loading ${descriptor.displayName}..."
    withContext(Dispatchers.IO) {
      engine.load(
        role = AgentRole.ORCHESTRATOR,
        model = descriptor,
        systemPrompt = systemPrompt,
        settings = settings,
      )
    }
    val runtimeMetadata = engine.metadata()
    appendSpine(
      type = SpineRecordType.PROVENANCE,
      payload =
        buildJsonObject {
          put("action", "model_loaded")
          put("model_name", descriptor.displayName)
          put("model_sha256", descriptor.sha256)
          put("runtime", runtimeMetadata?.runtimeName ?: descriptor.runtimeType.name)
          put("runtime_version", runtimeMetadata?.runtimeVersion ?: "unknown")
          descriptor.visionProjectorSha256?.let { put("projector_sha256", it) }
          descriptor.sourceRepository?.let { put("source_repository", it) }
          descriptor.sourceRevision?.let { put("source_revision", it) }
        },
    )
    status = "${descriptor.displayName} loaded."
  }

  private fun updateMessage(id: String, transform: (UiMessage) -> UiMessage) {
    messages = messages.map { message -> if (message.id == id) transform(message) else message }
  }

  private fun applyModelSettingsState(
    nextSettings: GenerationSettings,
    nextSystemPrompt: String,
    nextContextSettings: ContextSettings,
  ) {
    settings = nextSettings
    systemPrompt = nextSystemPrompt
    contextSettings = nextContextSettings
    if (!nextSettings.imageInputEnabled) pendingImage = null
    preferences
      .edit()
      .putInt(KEY_MAX_TOKENS, nextSettings.maxTokens)
      .putInt(KEY_TOP_K, nextSettings.topK)
      .putString(KEY_TOP_P, nextSettings.topP.toString())
      .putString(KEY_TEMPERATURE, nextSettings.temperature.toString())
      .putBoolean(KEY_IMAGE_INPUT_ENABLED, nextSettings.imageInputEnabled)
      .putString(KEY_SYSTEM_PROMPT, nextSystemPrompt)
      .putInt(KEY_CONTEXT_RESERVE, nextContextSettings.reservedOutputTokens)
      .apply()
  }

  private fun buildContextSnapshot(
    draftPrompt: String,
    attachedSkills: List<SkillRecord>,
  ): ContextSnapshot =
    ContextWindowManager.snapshot(
      contextBuildInput(
        draftPrompt = draftPrompt,
        attachedSkills = attachedSkills,
      )
    )

  private fun compressContextSnapshot(
    draftPrompt: String,
    attachedSkills: List<SkillRecord>,
    forceOne: Boolean,
  ): ContextSnapshot =
    ContextWindowManager.compress(
      input =
        contextBuildInput(
          draftPrompt = draftPrompt,
          attachedSkills = attachedSkills,
        ),
      forceOne = forceOne,
    )

  private fun contextBuildInput(
    draftPrompt: String,
    attachedSkills: List<SkillRecord>,
  ): ContextBuildInput {
    val fixedEntries =
      buildList {
        add(
          FixedContextEntry(
            id = "system-prompt",
            label = "System prompt",
            content = getSystemPrompt(AgentRole.ORCHESTRATOR),
          )
        )
        attachedSkills.forEach { skill ->
          add(
            FixedContextEntry(
              id = "skill:${skill.id}",
              label = "Skill: ${skill.name}",
              content = skill.source,
              policy = RetentionPolicy.SAFE_RETENTION,
            )
          )
        }
      }
    val managedMessages =
      messages
        .filter { it.role != MessageRole.ERROR && it.content.isNotBlank() }
        .mapIndexed { index, message ->
          ManagedContextMessage(
            id = message.id,
            label = "${message.role.contextLabel()} ${index + 1}",
            content = message.contextContent(),
            policy = message.retentionPolicy,
          )
        }
    return ContextBuildInput(
      systemPrompt = getSystemPrompt(AgentRole.ORCHESTRATOR),
      totalTokens = settings.maxTokens,
      settings = contextSettings,
      fixedEntries = fixedEntries,
      messages = managedMessages,
      compressedMessageIds = compressedMessageIds,
      recordedCompressionOperations = contextCompressionOperations,
      draftPrompt = draftPrompt,
      promptBuilder = { history, draft ->
        ContextPrompt.compose(
          userPrompt = draft,
          skills = attachedSkills,
          conversationContext = history,
        )
      },
    )
  }

  override fun close() {
    controllerScope.cancel()
    engine.close()
    remoteApiClient.cancel()
  }

  fun updateHfToken(token: String) {
    val normalized = token.trim()
    if (securePreferences.edit().putString("hf_token", normalized).commit()) {
      hfToken = normalized
      status =
        if (normalized.isEmpty()) {
          "Hugging Face access token removed."
        } else {
          "Hugging Face access token saved securely on this device."
        }
    } else {
      status = "Hugging Face access token could not be saved."
    }
  }

  fun updateGeminiApiKey(key: String) {
    updateApiProviders(apiConfiguration, key, deepSeekApiKey)
  }

  fun updateApiProviders(
    configuration: ApiProviderConfiguration,
    nextGeminiApiKey: String,
    nextDeepSeekApiKey: String,
  ): Boolean {
    if (isBusy) {
      status = "Wait for the current operation to finish."
      return false
    }
    val normalizedGemini = nextGeminiApiKey.trim()
    val normalizedDeepSeek = nextDeepSeekApiKey.trim()
    val normalizedGeminiModel = configuration.geminiModel.trim()
    val normalizedDeepSeekModel = configuration.deepSeekModel.trim()
    if (!normalizedGeminiModel.matches(API_MODEL_ID) || !normalizedDeepSeekModel.matches(API_MODEL_ID)) {
      status = "Use a valid API model ID."
      return false
    }
    if (configuration.backend == InferenceBackend.GEMINI && normalizedGemini.isEmpty()) {
      status = "Save a Gemini API key before selecting Gemini."
      return false
    }
    if (configuration.backend == InferenceBackend.DEEPSEEK && normalizedDeepSeek.isEmpty()) {
      status = "Save a DeepSeek API key before selecting DeepSeek."
      return false
    }
    val credentialsSaved =
      securePreferences
        .edit()
        .putString("gemini_api_key", normalizedGemini)
        .putString("deepseek_api_key", normalizedDeepSeek)
        .commit()
    if (!credentialsSaved) {
      status = "API credentials could not be saved."
      return false
    }
    val settingsSaved =
      preferences
        .edit()
        .putString(KEY_INFERENCE_BACKEND, configuration.backend.wireValue)
        .putString(KEY_GEMINI_MODEL, normalizedGeminiModel)
        .putString(KEY_DEEPSEEK_MODEL, normalizedDeepSeekModel)
        .commit()
    if (!settingsSaved) {
      status = "API credentials were secured, but provider settings could not be saved."
      return false
    }
    geminiApiKey = normalizedGemini
    deepSeekApiKey = normalizedDeepSeek
    geminiModel = normalizedGeminiModel
    deepSeekModel = normalizedDeepSeekModel
    inferenceBackend = configuration.backend
    pendingImage = null
    messages = emptyList()
    resetConversationTracking()
    activeSessionId = sessionId
    status = "${configuration.backend.label} selected."
    controllerScope.launch {
      appendSpine(
        type = SpineRecordType.PROVENANCE,
        payload =
          buildJsonObject {
            put("action", "inference_backend_selected")
            put("backend", configuration.backend.wireValue)
            put("model", activeBackendModelLabel())
          },
      )
    }
    return true
  }

  private fun persistInferenceBackend(backend: InferenceBackend) {
    inferenceBackend = backend
    preferences.edit().putString(KEY_INFERENCE_BACKEND, backend.wireValue).apply()
  }

  private fun activeApiKey(): String =
    when (inferenceBackend) {
      InferenceBackend.GEMINI -> geminiApiKey
      InferenceBackend.DEEPSEEK -> deepSeekApiKey
      InferenceBackend.LOCAL -> ""
    }

  private fun activeBackendModelLabel(): String =
    when (inferenceBackend) {
      InferenceBackend.LOCAL -> model?.displayName ?: "unloaded"
      InferenceBackend.GEMINI -> geminiModel
      InferenceBackend.DEEPSEEK -> deepSeekModel
    }

  private fun loadGenerationSettings(): GenerationSettings =
    GenerationSettings(
      maxTokens = preferences.getInt(KEY_MAX_TOKENS, 4000),
      topK = preferences.getInt(KEY_TOP_K, 64),
      topP = preferences.getString(KEY_TOP_P, "0.95")?.toDoubleOrNull() ?: 0.95,
      temperature = preferences.getString(KEY_TEMPERATURE, "1.0")?.toDoubleOrNull() ?: 1.0,
      imageInputEnabled = preferences.getBoolean(KEY_IMAGE_INPUT_ENABLED, false),
    )

  private fun loadContextSettings(): ContextSettings =
    ContextSettings(
        mode = ContextMode.fromWireValue(preferences.getString(KEY_CONTEXT_MODE, null)),
        compressionThresholdPercent = preferences.getInt(KEY_CONTEXT_THRESHOLD, 70),
        reservedOutputTokens = preferences.getInt(KEY_CONTEXT_RESERVE, 1024),
      )
      .normalized(settings.maxTokens)

  fun loadConversation(id: String) {
    if (isBusy || id == sessionId) return
    controllerScope.launch {
      isBusy = true
      status = "Loading conversation..."
      try {
        val restored = withContext(Dispatchers.IO) { conversationLedger.loadMessagesForSession(id) }
        messages = restored
        sessionId = id
        activeSessionId = id
        pendingImage = null
        compressedMessageIds = emptySet()
        contextCompressionOperations = emptyList()
        nextTurnIndex = restored.count { it.role == MessageRole.USER }
        lastTraceId = restored.lastOrNull { it.traceId != null }?.traceId
        conversationTurns =
          restored
            .chunked(2)
            .mapIndexedNotNull { index, pair ->
              val user = pair.getOrNull(0)?.takeIf { it.role == MessageRole.USER } ?: return@mapIndexedNotNull null
              val assistant = pair.getOrNull(1)?.takeIf { it.role == MessageRole.ASSISTANT } ?: return@mapIndexedNotNull null
              ConversationTurn(index, user.content, assistant.content)
            }
        engine.resetAllConversations()
        appendSpine(
          type = SpineRecordType.CONTINUITY_LOG,
          payload =
            buildJsonObject {
              put("action", "conversation_loaded")
              put("message_count", restored.size)
            },
        )
        status = "Conversation restored."
      } catch (error: Throwable) {
        status = error.message ?: "Conversation could not be restored."
      } finally {
        isBusy = false
      }
    }
  }

  fun archiveConversation(id: String) {
    if (isBusy) return
    controllerScope.launch {
      withContext(Dispatchers.IO) { conversationLedger.archive(id) }
      appendSpine(
        type = SpineRecordType.CONTINUITY_LOG,
        payload =
          buildJsonObject {
            put("action", "conversation_archived")
            put("conversation_id", id)
          },
      )
      if (id == sessionId) newConversation()
      refreshConversations()
    }
  }

  fun archiveRuntimeSpine() {
    if (isBusy) return
    controllerScope.launch {
      val archived = withContext(Dispatchers.IO) { runtimeSpine.archive() }
      status =
        if (archived == null) "Runtime Spine is empty."
        else "Runtime Spine archived locally as ${archived.name}."
      refreshRuntimeSpine()
    }
  }

  fun getSystemPrompt(role: AgentRole): String {
    val personaId = activePersonaIds[role]
    if (personaId != null) {
      val persona = personas.find { it.id == personaId }
      if (persona != null) return persona.systemPrompt
    }
    return if (role == AgentRole.ORCHESTRATOR) systemPrompt else "You are a helpful expert assistant."
  }

  fun setActivePersona(role: AgentRole, id: String?) {
    val newMap = activePersonaIds.toMutableMap()
    if (id == null) {
      newMap.remove(role)
    } else {
      newMap[role] = id
    }
    activePersonaIds = newMap
  }

  fun clearArchives() {
    archiveSize = 0L
  }

  fun clearBlobs() {
    blobStoreSize = 0L
  }

  fun toggleMemory(id: String) {
    selectedMemoryIds = if (id in selectedMemoryIds) selectedMemoryIds - id else selectedMemoryIds + id
  }

  private fun MessageRole.contextLabel(): String =
    when (this) {
      MessageRole.USER -> "User"
      MessageRole.ASSISTANT -> "Assistant"
      MessageRole.ERROR -> "Error"
    }

  private fun UiMessage.contextContent(): String =
    buildString {
      image?.let {
        appendLine(
          "[Image attached: ${it.displayName}; ${it.width}x${it.height}; sha256:${it.sha256}]"
        )
      }
      append(content)
    }

  private companion object {
    const val DEFAULT_SYSTEM_PROMPT = "You are a concise, helpful on-device assistant."
    const val KEY_TRACE_ENABLED = "trace_enabled"
    const val KEY_SPINE_DEFAULT_APPLIED = "runtime_spine_default_v1"
    const val KEY_SKILLS_SEEDED = "skills_seeded_v1"
    const val KEY_SELECTED_SKILLS = "selected_skill_ids"
    const val KEY_THEME_MODE = "theme_mode"
    const val KEY_MAX_TOKENS = "max_tokens"
    const val KEY_TOP_K = "top_k"
    const val KEY_TOP_P = "top_p"
    const val KEY_TEMPERATURE = "temperature"
    const val KEY_IMAGE_INPUT_ENABLED = "image_input_enabled"
    const val KEY_SYSTEM_PROMPT = "system_prompt"
    const val KEY_CONTEXT_MODE = "context_mode"
    const val KEY_CONTEXT_THRESHOLD = "context_threshold"
    const val KEY_CONTEXT_RESERVE = "context_reserve"
    const val KEY_INFERENCE_BACKEND = "inference_backend"
    const val KEY_GEMINI_MODEL = "gemini_model"
    const val KEY_DEEPSEEK_MODEL = "deepseek_model"
    const val DEFAULT_GEMINI_MODEL = "gemini-3.5-flash"
    const val DEFAULT_DEEPSEEK_MODEL = "deepseek-v4-flash"
    val API_MODEL_ID = Regex("[A-Za-z0-9._:/-]{1,160}")
  }
}
