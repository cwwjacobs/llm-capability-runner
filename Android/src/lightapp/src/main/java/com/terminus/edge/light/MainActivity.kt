package com.terminus.edge.light

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.terminus.edge.light.trace.ReviewDecision
import com.terminus.edge.light.trace.ReviewRubric
import com.terminus.edge.light.image.ImageAttachment
import com.terminus.edge.light.model.ModelDownloaderDialog
import com.terminus.edge.light.workflow.WorkflowDraftStore
import java.io.File
import java.text.DecimalFormat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      val context = androidx.compose.ui.platform.LocalContext.current
      val controller = remember { EdgeController(context.applicationContext) }
      EdgeLightTheme(controller.themeMode) {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = MaterialTheme.colorScheme.background,
        ) {
          EdgeLightScreen(controller)
        }
      }
    }
  }
}

@Composable
private fun EdgeLightScreen(controller: EdgeController) {
  val scope = rememberCoroutineScope()
  val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
  val notesStore = remember { HumanNotesStore(appContext) }
  val workflowStore = remember { WorkflowDraftStore(appContext) }
  var notes by remember { mutableStateOf(notesStore.activeNotes()) }
  var workflows by remember { mutableStateOf(workflowStore.activeDrafts()) }
  var prompt by remember { mutableStateOf("") }
  var pendingReview by remember { mutableStateOf<Pair<UiMessage, ReviewDecision>?>(null) }
  var pendingExport by remember { mutableStateOf<ExportMode?>(null) }
  var showDeleteConfirmation by remember { mutableStateOf(false) }
  var showSkillLibrary by remember { mutableStateOf(false) }
  var showAddSkill by remember { mutableStateOf(false) }
  var showSettings by remember { mutableStateOf(false) }
  var showContextManager by remember { mutableStateOf(false) }
  var showNotes by remember { mutableStateOf(false) }
  var showWorkflows by remember { mutableStateOf(false) }
  var showHuggingFaceAccess by remember { mutableStateOf(false) }
  var showModelDownloader by remember { mutableStateOf(false) }
  var showDeviceModels by remember { mutableStateOf(false) }
  var showApiProviders by remember { mutableStateOf(false) }
  var destination by remember { mutableStateOf(WorkspaceDestination.CHAT) }

  val importModel =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) scope.launch { controller.importModel(uri) }
    }
  val exportRaw =
    rememberLauncherForActivityResult(
      ActivityResultContracts.CreateDocument("application/x-ndjson")
    ) { uri ->
      if (uri != null) controller.export(uri, ExportMode.RAW, scope)
    }
  val exportCurated =
    rememberLauncherForActivityResult(
      ActivityResultContracts.CreateDocument("application/x-ndjson")
    ) { uri ->
      if (uri != null) controller.export(uri, ExportMode.CURATED, scope)
    }
  val exportReplay =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
      uri ->
      if (uri != null) controller.export(uri, ExportMode.REPLAY, scope)
    }
  val importSkills =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
      controller.importSkills(uris, scope)
    }
  val importImage =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) scope.launch { controller.attachImage(uri) }
    }

  LaunchedEffect(Unit) { controller.restoreModel() }
  DisposableEffect(Unit) { onDispose { controller.close() } }
  val contextSnapshot = controller.contextSnapshot(prompt)

  WorkspaceDrawer(
    destination = destination,
    onDestination = { destination = it },
    onNewConversation = controller::newConversation,
    scope = scope,
  ) { openDrawer ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .safeDrawingPadding()
          .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        WorkspaceMenuButton(onClick = openDrawer)
        if (destination == WorkspaceDestination.CHAT) {
          ContextMeter(
            snapshot = contextSnapshot,
            onClick = { showContextManager = true },
            modifier = Modifier.weight(1f),
          )
        } else {
          Text(
            if (destination == WorkspaceDestination.CONVERSATIONS) "Conversations" else "Runtime Spine",
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium,
          )
        }
        IconButton(onClick = { showSettings = true }) {
          Icon(Icons.Rounded.Settings, contentDescription = "Settings")
        }
      }

      when (destination) {
        WorkspaceDestination.CONVERSATIONS ->
          ConversationsScreen(
            conversations = controller.conversations,
            activeSessionId = controller.activeSessionId,
            onOpen = {
              controller.loadConversation(it)
              destination = WorkspaceDestination.CHAT
            },
            onArchive = controller::archiveConversation,
          )
        WorkspaceDestination.RUNTIME_SPINE ->
          RuntimeSpineScreen(
            result = controller.spineReadResult,
            onRefresh = { scope.launch { controller.refreshRuntimeSpine() } },
            onArchive = controller::archiveRuntimeSpine,
          )
        WorkspaceDestination.CHAT -> {
          MainModelBar(
            modelLabel = controller.activeInferenceLabel,
            ready = controller.inferenceReady,
            isBusy = controller.isBusy,
            onOpenModels = {
              showDeviceModels = true
              scope.launch { controller.scanModels() }
            },
            onBrowseHuggingFace = { showModelDownloader = true },
          )
          ChatPanel(
            messages = controller.messages,
            compressedMessageIds = contextSnapshot.compressedEntryIds.toSet(),
            themeMode = controller.themeMode,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            onKeep = { message -> pendingReview = message to ReviewDecision.KEEP },
            onReject = { message -> pendingReview = message to ReviewDecision.REJECT },
            onEdit = { message -> pendingReview = message to ReviewDecision.EDITED },
          )

          if (
            controller.isBusy ||
              controller.status.contains("failed", ignoreCase = true) ||
              controller.status.contains("error", ignoreCase = true) ||
              controller.status.contains("stopped", ignoreCase = true)
          ) {
            Text(
              controller.status,
              color =
                if (controller.status.contains("failed", true) || controller.status.contains("error", true)) {
                  MaterialTheme.colorScheme.error
                } else {
                  MaterialTheme.colorScheme.primary
                },
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              style = MaterialTheme.typography.labelSmall,
            )
          }

          ContextPressureNotice(
            snapshot = contextSnapshot,
            onManage = { showContextManager = true },
            onCompress = { controller.compressContext(prompt) },
          )

          ComposerControlStrip(
            enabled = !controller.isBusy,
            onSkills = { showSkillLibrary = true },
          )

          controller.pendingImage?.let { image ->
            PendingImageCard(image = image, onRemove = controller::removePendingImage)
          }
          IntegratedComposer(
            value = prompt,
            onValueChange = { prompt = it },
            modelReady = controller.inferenceReady,
            isBusy = controller.isBusy,
            imageEnabled = controller.imageInputAvailable,
            hasPendingImage = controller.pendingImage != null,
            onAddImage = { importImage.launch(arrayOf("image/*")) },
            onSend = { if (controller.send(prompt)) prompt = "" },
            onStop = controller::cancel,
          )
        }
      }
    }
  }

  pendingReview?.let { (message, decision) ->
    ReviewDialog(
      message = message,
      decision = decision,
      onDismiss = { pendingReview = null },
      onSave = { correctedResponse, note, tags, rubric ->
        controller.review(
          messageId = message.id,
          decision = decision,
          correctedResponse = correctedResponse,
          note = note,
          tags = tags,
          rubric = rubric,
          scope = scope,
        )
        pendingReview = null
      },
    )
  }

  pendingExport?.let { mode ->
    AlertDialog(
      onDismissRequest = { pendingExport = null },
      title = { Text("Export ${mode.name.lowercase()} data?") },
      text = {
        Text(
          when (mode) {
            ExportMode.RAW ->
               "Raw JSONL contains full prompts, responses, system prompts, and Runtime Spine metadata in plaintext."
            ExportMode.CURATED ->
              "Curated JSONL contains operator-approved training candidates in plaintext. Rights remain unverified."
            ExportMode.REPLAY ->
              "The Replay Pack contains full trace history, context snapshots, and the exact model binary. It may be several gigabytes."
          }
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            pendingExport = null
            when (mode) {
              ExportMode.RAW -> exportRaw.launch("edge-light-raw.jsonl")
              ExportMode.CURATED -> exportCurated.launch("edge-light-training.jsonl")
              ExportMode.REPLAY -> exportReplay.launch("runtime-spine-replay-pack.zip")
            }
          }
        ) {
          Text("Export")
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingExport = null }) { Text("Cancel") }
      },
    )
  }

  if (showDeleteConfirmation) {
    AlertDialog(
      onDismissRequest = { showDeleteConfirmation = false },
      title = { Text("Archive current runtime records?") },
      text = {
        Text(
          "This moves the active Runtime Spine, legacy trace ledger, and snapshots into a timestamped local archive. Nothing is deleted."
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            controller.archiveTraces(scope)
            showDeleteConfirmation = false
          }
        ) {
          Text("Archive")
        }
      },
      dismissButton = {
        TextButton(onClick = { showDeleteConfirmation = false }) { Text("Cancel") }
      },
    )
  }

  if (showSkillLibrary) {
    SkillLibraryDialog(
      skills = controller.skills,
      selectedIds = controller.selectedSkillIds,
      onDismiss = { showSkillLibrary = false },
      onToggle = controller::toggleSkill,
      onImport = { importSkills.launch(arrayOf("text/markdown", "text/plain", "*/*")) },
      onAdd = {
        showSkillLibrary = false
        showAddSkill = true
      },
    )
  }

  if (showAddSkill) {
    AddSkillDialog(
      onDismiss = { showAddSkill = false },
      onCreate = controller::addSkill,
    )
  }

  if (showSettings) {
    SettingsDialog(
      themeMode = controller.themeMode,
      settings = controller.settings,
      contextSettings = controller.contextSettings,
      systemPrompt = controller.systemPrompt,
      modelLabel = controller.activeInferenceLabel,
      isBusy = controller.isBusy,
      traceEnabled = controller.traceEnabled,
      traceStats = controller.traceStats,
      onDismiss = { showSettings = false },
      onThemeChange = controller::updateThemeMode,
      onOpenDeviceModels = {
        showSettings = false
        showDeviceModels = true
        scope.launch { controller.scanModels() }
      },
      onOpenApiProviders = {
        showSettings = false
        showApiProviders = true
      },
      onImportModel = { importModel.launch(arrayOf("*/*")) },
      onTraceChange = { enabled -> controller.updateTraceEnabled(enabled, scope) },
      onExportRaw = {
        showSettings = false
        pendingExport = ExportMode.RAW
      },
      onExportCurated = {
        showSettings = false
        pendingExport = ExportMode.CURATED
      },
      onExportReplay = {
        showSettings = false
        pendingExport = ExportMode.REPLAY
      },
      onArchiveTraces = {
        showSettings = false
        showDeleteConfirmation = true
      },
      onOpenNotes = {
        showSettings = false
        showNotes = true
      },
      onOpenWorkflows = {
        showSettings = false
        showWorkflows = true
      },
      onOpenHuggingFaceAccess = {
        showSettings = false
        showHuggingFaceAccess = true
      },
      onDownloadModel = {
        showSettings = false
        showModelDownloader = true
      },
      onApplyModelSettings = { settings, systemPrompt ->
        controller.updateModelSettings(settings, systemPrompt, scope)
      },
      onApplyContextSettings = controller::updateContextSettings,
    )
  }

  if (showNotes) {
    HumanNotesDialog(
      notes = notes,
      onDismiss = { showNotes = false },
      onSave = { id, title, body -> notes = notesStore.save(id, title, body) },
      onArchive = { id -> notes = notesStore.archive(id) },
    )
  }

  if (showWorkflows) {
    WorkflowDraftsDialog(
      drafts = workflows,
      onDismiss = { showWorkflows = false },
      onSave = { id, name, goal, steps ->
        workflows = workflowStore.save(id, name, goal, steps)
      },
      onArchive = { id -> workflows = workflowStore.archive(id) },
    )
  }

  if (showHuggingFaceAccess) {
    HuggingFaceAccessDialog(
      storedToken = controller.hfToken,
      onDismiss = { showHuggingFaceAccess = false },
      onSave = controller::updateHfToken,
    )
  }

  if (showModelDownloader) {
    ModelDownloaderDialog(
      onDismiss = { showModelDownloader = false },
      onModelDownloaded = { downloaded ->
        showModelDownloader = false
        scope.launch { controller.selectDownloadedModel(downloaded) }
      },
      downloadDir = File(appContext.filesDir, "models"),
      hfToken = controller.hfToken,
    )
  }

  if (showDeviceModels) {
    DeviceModelsDialog(
      models = controller.scannedModels,
      activeModelPath = controller.model?.path,
      isBusy = controller.isBusy,
      onDismiss = { showDeviceModels = false },
      onRefresh = { scope.launch { controller.scanModels() } },
      onImport = {
        showDeviceModels = false
        importModel.launch(arrayOf("*/*"))
      },
      onSelect = { file ->
        scope.launch {
          controller.selectScannedModel(file)
          if (!controller.isBusy) showDeviceModels = false
        }
      },
      onArchive = { file -> scope.launch { controller.archiveScannedModel(file) } },
      onArchiveStale = { scope.launch { controller.archiveStaleModels() } },
    )
  }

  if (showApiProviders) {
    ApiProvidersDialog(
      configuration = controller.apiConfiguration,
      storedGeminiKey = controller.geminiApiKey,
      storedDeepSeekKey = controller.deepSeekApiKey,
      onDismiss = { showApiProviders = false },
      onSave = controller::updateApiProviders,
    )
  }

  if (showContextManager) {
    ContextManagerSheet(
      snapshot = contextSnapshot,
      onDismiss = { showContextManager = false },
      onPolicyChange = controller::updateMessageRetention,
      onCompress = { controller.compressContext(prompt) },
      onRestore = controller::restoreCompressedContext,
      onClearTemporary = controller::clearTemporaryContext,
      onNewConversation = controller::newConversation,
    )
  }
}

@Composable
private fun PendingImageCard(image: ImageAttachment, onRemove: () -> Unit) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    border = edgeLightBorder(),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Image(
        bitmap = image.bitmap.asImageBitmap(),
        contentDescription = image.displayName,
        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
        contentScale = ContentScale.Crop,
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(image.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
          "${image.width}x${image.height} | ${formatBytes(image.pngBytes.size.toLong())}",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelSmall,
        )
        Text(
          "Sent locally with the next message.",
          color = MaterialTheme.colorScheme.primary,
          style = MaterialTheme.typography.labelSmall,
        )
      }
      TextButton(onClick = onRemove) { Text("Remove") }
    }
  }
}

@Composable
private fun ReviewDialog(
  message: UiMessage,
  decision: ReviewDecision,
  onDismiss: () -> Unit,
  onSave: (String?, String?, List<String>, ReviewRubric) -> Unit,
) {
  var correctedResponse by
    remember(message.id, decision) {
      mutableStateOf(if (decision == ReviewDecision.EDITED) message.content else "")
    }
  var note by remember(message.id, decision) { mutableStateOf("") }
  var tags by remember(message.id, decision) { mutableStateOf("") }
  var correctness by remember(message.id, decision) { mutableStateOf("") }
  var usefulness by remember(message.id, decision) { mutableStateOf("") }
  var groundedness by remember(message.id, decision) { mutableStateOf("") }
  var safety by remember(message.id, decision) { mutableStateOf("") }
  val scoreValues = listOf(correctness, usefulness, groundedness, safety)
  val scoresValid = scoreValues.all { it.isBlank() || it.toIntOrNull() in 1..5 }
  val responseValid = decision != ReviewDecision.EDITED || correctedResponse.isNotBlank()

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Review: ${decision.wireValue}") },
    text = {
      Column(
        modifier =
          Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(9.dp),
      ) {
        if (decision == ReviewDecision.EDITED) {
          OutlinedTextField(
            value = correctedResponse,
            onValueChange = { correctedResponse = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Corrected response") },
            minLines = 5,
          )
        }
        OutlinedTextField(
          value = note,
          onValueChange = { note = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Review reason (optional)") },
          minLines = 2,
        )
        OutlinedTextField(
          value = tags,
          onValueChange = { tags = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Quality tags, comma separated") },
          singleLine = true,
        )
        Text(
          "Optional rubric scores, 1-5",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelMedium,
        )
        ReviewScoreField("Correctness", correctness) { correctness = it }
        ReviewScoreField("Usefulness", usefulness) { usefulness = it }
        ReviewScoreField("Groundedness", groundedness) { groundedness = it }
        ReviewScoreField("Safety", safety) { safety = it }
        if (!scoresValid) {
          Text(
            "Scores must be blank or between 1 and 5.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          onSave(
            correctedResponse.takeIf { decision == ReviewDecision.EDITED }?.trim(),
            note.trim().ifEmpty { null },
            tags.split(',').map(String::trim).filter(String::isNotEmpty),
            ReviewRubric(
              correctness = correctness.toIntOrNull(),
              usefulness = usefulness.toIntOrNull(),
              groundedness = groundedness.toIntOrNull(),
              safety = safety.toIntOrNull(),
            ),
          )
        },
        enabled = scoresValid && responseValid,
      ) {
        Text("Save review")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Composable
private fun ReviewScoreField(label: String, value: String, onValueChange: (String) -> Unit) {
  OutlinedTextField(
    value = value,
    onValueChange = { next -> if (next.length <= 1 && next.all(Char::isDigit)) onValueChange(next) },
    modifier = Modifier.fillMaxWidth(),
    label = { Text(label) },
    placeholder = { Text("Not scored") },
    singleLine = true,
  )
}

@Composable
private fun ComposerControlStrip(
  enabled: Boolean,
  onSkills: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(7.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    GradientPillButton(
      text = "Skills",
      onClick = onSkills,
      enabled = enabled,
      contentPadding = PaddingValues(horizontal = 11.dp, vertical = 7.dp),
    )
  }
}

@Composable
private fun MainModelBar(
  modelLabel: String,
  ready: Boolean,
  isBusy: Boolean,
  onOpenModels: () -> Unit,
  onBrowseHuggingFace: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    border = edgeLightBorder(),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          if (ready) "Model loaded" else "Model required",
          color = if (ready) EdgeLightPalette.Cyan else MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.labelSmall,
        )
        Text(
          modelLabel,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          fontWeight = FontWeight.SemiBold,
          style = MaterialTheme.typography.bodyMedium,
        )
      }
      OutlinedButton(onClick = onOpenModels, enabled = !isBusy) {
        Text("Models")
      }
      GradientPillButton(
        text = "HF",
        onClick = onBrowseHuggingFace,
        enabled = !isBusy,
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 9.dp),
      )
    }
  }
}

@Composable
private fun IntegratedComposer(
  value: String,
  onValueChange: (String) -> Unit,
  modelReady: Boolean,
  isBusy: Boolean,
  imageEnabled: Boolean,
  hasPendingImage: Boolean,
  onAddImage: () -> Unit,
  onSend: () -> Unit,
  onStop: () -> Unit,
) {
  val enabled = modelReady && !isBusy
  val canSend = modelReady && !isBusy && (value.isNotBlank() || hasPendingImage)
  val shape = RoundedCornerShape(18.dp)
  val borderColor =
    if (modelReady) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)

  Box(modifier = Modifier.fillMaxWidth().padding(top = 7.dp)) {
    Row(
      modifier =
        Modifier.fillMaxWidth()
          .border(2.dp, borderColor, shape)
          .padding(start = 6.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
      verticalAlignment = Alignment.Bottom,
      horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.weight(1f),
        placeholder = { Text("Message") },
        enabled = enabled,
        minLines = 1,
        maxLines = 6,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
        colors =
          OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            disabledBorderColor = Color.Transparent,
          ),
      )
      IconButton(
        onClick = onAddImage,
        enabled = modelReady && !isBusy && !hasPendingImage,
        modifier = Modifier.padding(bottom = 4.dp),
      ) {
        Icon(
          imageVector = Icons.Rounded.PhotoLibrary,
          contentDescription =
            if (imageEnabled) "Add image" else "Images are not supported by the loaded model",
          tint =
            when {
              !modelReady || isBusy || hasPendingImage -> MaterialTheme.colorScheme.onSurfaceVariant
              imageEnabled -> EdgeLightPalette.Cyan
              else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
      }
      if (isBusy) {
        OutlinedButton(
          onClick = onStop,
          shape = RoundedCornerShape(50),
          modifier = Modifier.padding(bottom = 5.dp),
        ) {
          Text("Stop")
        }
      } else {
        GradientPillButton(
          text = "Send",
          onClick = onSend,
          enabled = canSend,
          modifier = Modifier.padding(bottom = 6.dp),
          contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        )
      }
    }
    Text(
      "Message",
      modifier =
        Modifier.align(Alignment.TopEnd)
          .offset(x = (-18).dp, y = (-8).dp)
          .background(MaterialTheme.colorScheme.background)
          .padding(horizontal = 7.dp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelMedium,
    )
  }
}

@Composable
private fun ChatPanel(
  messages: List<UiMessage>,
  compressedMessageIds: Set<String>,
  themeMode: EdgeThemeMode,
  modifier: Modifier,
  onKeep: (UiMessage) -> Unit,
  onReject: (UiMessage) -> Unit,
  onEdit: (UiMessage) -> Unit,
) {
  val background =
    if (themeMode == EdgeThemeMode.LIGHT) MaterialTheme.colorScheme.surface
    else EdgeLightPalette.ChatBlack
  Box(
    modifier =
      modifier
        .clip(RoundedCornerShape(14.dp))
        .background(background)
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
        .padding(8.dp),
  ) {
    if (messages.isEmpty()) {
      Text(
        "Conversation stays on this device.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.align(Alignment.Center),
        style = MaterialTheme.typography.bodySmall,
      )
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(7.dp),
      ) {
        items(messages, key = UiMessage::id) { message ->
          MessageCard(
            message = message,
            compressed = message.id in compressedMessageIds,
            onKeep = { onKeep(message) },
            onReject = { onReject(message) },
            onEdit = { onEdit(message) },
          )
        }
      }
    }
  }
}

@Composable
private fun MessageCard(
  message: UiMessage,
  compressed: Boolean,
  onKeep: () -> Unit,
  onReject: () -> Unit,
  onEdit: () -> Unit,
) {
  val background =
    when (message.role) {
      MessageRole.USER -> MaterialTheme.colorScheme.primaryContainer
      MessageRole.ASSISTANT -> MaterialTheme.colorScheme.surfaceVariant
      MessageRole.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
  val contentColor =
    when (message.role) {
      MessageRole.USER -> MaterialTheme.colorScheme.onPrimaryContainer
      MessageRole.ASSISTANT -> MaterialTheme.colorScheme.onSecondaryContainer
      MessageRole.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    colors = CardDefaults.cardColors(containerColor = background),
    border =
      BorderStroke(
        1.dp,
        when (message.role) {
          MessageRole.USER -> EdgeLightPalette.Gold.copy(alpha = 0.72f)
          MessageRole.ASSISTANT -> EdgeLightPalette.Cyan.copy(alpha = 0.72f)
          MessageRole.ERROR -> MaterialTheme.colorScheme.error
        },
      ),
  ) {
    Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
      Text(
        when (message.role) {
          MessageRole.USER -> "You"
          MessageRole.ASSISTANT -> "Assistant"
          MessageRole.ERROR -> "Error"
        },
        color =
          when (message.role) {
            MessageRole.USER -> EdgeLightPalette.HotPink
            MessageRole.ASSISTANT -> EdgeLightPalette.Cyan
            MessageRole.ERROR -> MaterialTheme.colorScheme.error
          },
        style = MaterialTheme.typography.labelMedium,
      )
      if (
        compressed ||
          message.retentionPolicy !=
            com.terminus.edge.light.context.RetentionPolicy.COMPRESSIBLE
      ) {
        Text(
          if (compressed) {
            "Density-compressed · original retained"
          } else {
            message.retentionPolicy.label
          },
          color =
            if (
              message.retentionPolicy ==
                com.terminus.edge.light.context.RetentionPolicy.PINNED ||
                message.retentionPolicy ==
                  com.terminus.edge.light.context.RetentionPolicy.SAFE_RETENTION
            ) {
              EdgeLightPalette.Gold
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
          style = MaterialTheme.typography.labelSmall,
        )
      }
      Spacer(Modifier.height(3.dp))
      message.image?.let { image ->
        Image(
          bitmap = image.bitmap.asImageBitmap(),
          contentDescription = image.displayName,
          modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(8.dp)),
          contentScale = ContentScale.Fit,
        )
        Text(
          "${image.displayName} | ${image.width}x${image.height}",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(5.dp))
      }
      Text(message.content.ifBlank { "..." }, color = contentColor)
      if (message.role == MessageRole.ASSISTANT && message.traceId != null) {
        Spacer(Modifier.height(6.dp))
        if (message.reviewDecision == null) {
          Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(onClick = onKeep, contentPadding = PaddingValues(horizontal = 8.dp)) {
              Text("Keep")
            }
            TextButton(onClick = onReject, contentPadding = PaddingValues(horizontal = 8.dp)) {
              Text("Reject")
            }
            TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 8.dp)) {
              Text("Edit")
            }
          }
        } else {
          Text(
            "Reviewed: ${message.reviewDecision.wireValue}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
          )
        }
      }
    }
  }
}

private fun formatBytes(bytes: Long): String {
  if (bytes < 1024) return "$bytes B"
  val units = arrayOf("KB", "MB", "GB")
  var value = bytes.toDouble()
  var index = -1
  while (value >= 1024 && index < units.lastIndex) {
    value /= 1024
    index += 1
  }
  return "${DecimalFormat("0.0").format(value)} ${units[index]}"
}
