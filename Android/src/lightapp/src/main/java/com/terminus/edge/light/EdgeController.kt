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
import com.terminus.edge.light.model.ModelDescriptor
import com.terminus.edge.light.model.ModelStore
import com.terminus.edge.light.model.StagedModel
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
  val agentStates = engine.agentStates
  private val traceRoot = File(context.filesDir, "traces")
  private val traceArtifactStore = TraceArtifactStore(File(traceRoot, "artifacts"))
  private val traceLedger =
    TraceLedger(File(traceRoot, "trace_events.jsonl"), artifactStore = traceArtifactStore)
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

  var status by mutableStateOf("Import a LiteRT-LM model to begin.")
    private set
  var traceEnabled by mutableStateOf(preferences.getBoolean(KEY_TRACE_ENABLED, false))
    private set
  var traceStats by mutableStateOf(traceLedger.stats())
    private set
  var skills by mutableStateOf<List<SkillRecord>>(emptyList())
    private set
  var selectedSkillIds by mutableStateOf<Set<String>>(emptySet())
    private set

  init {
    seedBundledSkills()
    refreshSkills()
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
      val descriptor = withContext(Dispatchers.IO) { modelStore.commitExternal(file) }
      loadModel(descriptor)
      status = "Model loaded successfully."
      scannedModels = emptyList() // clear after picking
    } catch (error: Throwable) {
      status = error.message ?: "Failed to load the selected model."
      previous?.let { loadModel(it) }
    } finally {
      isBusy = false
    }
  }

  suspend fun restoreModel() {
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
    if (!settings.imageInputEnabled) {
      status = "Enable image input in Settings before attaching an image."
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
    val activeModel = model ?: return false
    val activeImage = pendingImage
    if (isBusy || (prompt.isBlank() && activeImage == null)) return false
    if (activeImage != null && !settings.imageInputEnabled) {
      status = "Enable image input in Settings and reload the model."
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
    pendingImage = null
    isBusy = true
    status =
      when {
        droppedCount > 0 -> "Truncated $droppedCount messages. Generating..."
        contextSnapshot.newlyCompressedIds.isEmpty() -> "Generating..."
        else -> "Compressed ${contextSnapshot.newlyCompressedIds.size} context entries. Generating..."
      }
    val recordThisResponse = traceEnabled

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
        withContext(Dispatchers.IO) { engine.resetConversation(AgentRole.ORCHESTRATOR) }
        if (recordThisResponse) {
          status = "Preparing trace snapshot..."
          runCatching {
              withContext(Dispatchers.IO) {
                createStartedTrace(
                  activeModel = activeModel,
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
        val response =
          engine.generate(
            role = AgentRole.ORCHESTRATOR,
            prompt = effectivePrompt,
            imageBytes = listOfNotNull(activeImage?.pngBytes),
          ) { chunk ->
            chunkCount.incrementAndGet()
            firstChunkAt.compareAndSet(-1L, SystemClock.elapsedRealtime())
            synchronized(partialResponse) { partialResponse.append(chunk) }
            controllerScope.launch(Dispatchers.Main.immediate) {
              updateMessage(assistantId) { it.copy(content = it.content + chunk) }
            }
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
        refreshTraceStats()
        status =
          when {
            traceError != null -> "Ready. Trace capture issue: ${traceError?.message ?: "unknown error"}"
            activeTrace != null -> "Response saved for review."
            else -> "Ready."
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
      } catch (error: Throwable) {
        conversationStateKnown = false
        updateMessage(assistantId) {
          it.copy(role = MessageRole.ERROR, content = error.message ?: "Generation failed.")
        }
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
      } finally {
        context.startService(Intent(context, RunnerForegroundService::class.java).apply { action = "STOP" })
        isBusy = false
      }
    }
    return true
  }

  fun cancel() {
    engine.cancel()
  }

  fun updateTraceEnabled(enabled: Boolean, scope: CoroutineScope) {
    if (isBusy) {
      status = "Wait for the current operation to finish."
      return
    }
    traceEnabled = enabled
    preferences.edit().putBoolean(KEY_TRACE_ENABLED, enabled).apply()
    status = if (enabled) "Trace recording enabled." else "Trace recording disabled."
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
        .onFailure { status = "Trace preference saved, but consent receipt failed: ${it.message}" }
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
    status = "New local conversation."
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
            modelPath = current.path,
            modelName = current.displayName,
            sizeBytes = current.sizeBytes,
            systemPrompt = systemPrompt,
            settings = settings,
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
                  modelPath = current.path,
                  modelName = current.displayName,
                  sizeBytes = current.sizeBytes,
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
                  traceLedger.exportRaw(output)
                  "Exported ${traceLedger.eventCount()} trace events."
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
                  val replay = traceLedger.exportReplay(output)
                  "Replay bundle: ${replay.traceCount} traces, ${replay.artifactCount} artifacts, ${replay.modelCount} model snapshot${if (replay.modelCount == 1) "" else "s"}."
                }
              }
            }
          }
        status = result
      } catch (error: Throwable) {
        status = error.message ?: "Export failed."
      } finally {
        isBusy = false
      }
    }
  }

  fun deleteTraces(scope: CoroutineScope) {
    if (isBusy) {
      status = "Wait for the current operation to finish."
      return
    }
    isBusy = true
    scope.launch {
      try {
        val deleted = withContext(Dispatchers.IO) { traceLedger.deleteAll() }
        if (deleted) {
          messages = messages.map { it.copy(traceId = null, reviewDecision = null) }
          refreshTraceStats()
          lastTraceId = null
          status = "Local traces deleted."
        } else {
          status = "Could not delete local traces."
        }
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
      runtimeName = "litertlm-android",
      runtimeVersion = BuildConfig.LITERT_LM_VERSION,
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
        modelPath = descriptor.path,
        modelName = descriptor.displayName,
        sizeBytes = descriptor.sizeBytes,
        systemPrompt = systemPrompt,
        settings = settings,
      )
    }
    status = "Ready."
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
  }

  fun updateHfToken(token: String) {
    hfToken = token
    securePreferences.edit().putString("hf_token", token).apply()
  }

  fun updateGeminiApiKey(key: String) {
    geminiApiKey = key
    securePreferences.edit().putString("gemini_api_key", key).apply()
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
    activeSessionId = id
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
  }
}
