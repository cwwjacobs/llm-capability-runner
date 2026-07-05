package com.terminus.edge.light

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.terminus.edge.light.trace.ReviewDecision
import com.terminus.edge.light.trace.ReviewRubric
import com.terminus.edge.light.image.ImageAttachment
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import android.os.Environment
import android.os.Build
import com.terminus.edge.light.model.ModelDownloaderDialog
import com.terminus.edge.light.persona.GateWarningDialog
import com.terminus.edge.light.model.ModelDescriptor
import com.terminus.edge.light.memory.MemoryDeckDialog
import com.terminus.edge.light.persona.PersonaSelector
import com.terminus.edge.light.agent.SwarmMonitorUi
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
  var prompt by remember { mutableStateOf("") }
  var pendingReview by remember { mutableStateOf<Pair<UiMessage, ReviewDecision>?>(null) }
  var pendingExport by remember { mutableStateOf<ExportMode?>(null) }
  var showDeleteConfirmation by remember { mutableStateOf(false) }
  var showSkillLibrary by remember { mutableStateOf(false) }
  var showAddSkill by remember { mutableStateOf(false) }
  var showReceipts by remember { mutableStateOf(false) }
  var showSettings by remember { mutableStateOf(false) }
  var showContextManager by remember { mutableStateOf(false) }
  var showMemoryDeck by remember { mutableStateOf(false) }
  var showStorageUi by remember { mutableStateOf(false) }
  var showModelDownloader by remember { mutableStateOf(false) }
  var showSwarmMonitor by remember { mutableStateOf(false) }

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

  val overlayPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) {}

  val context = androidx.compose.ui.platform.LocalContext.current

  val mediaProjectionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK && result.data != null) {
      BubbleOverlayService.mediaProjectionIntent = result.data
      BubbleOverlayService.onImageCaptured = { file ->
        val uri = android.net.Uri.fromFile(file)
        scope.launch { controller.attachImage(uri) }
      }
      val serviceIntent = Intent(context, BubbleOverlayService::class.java)
      context.startForegroundService(serviceIntent)
    }
  }

  val manageFilesLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
        scope.launch { controller.scanModels() }
      }
    }

  val authManager = remember { com.terminus.edge.light.model.HuggingFaceAuthManager(context) }
  val authLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK && result.data != null) {
      authManager.handleAuthResponse(result.data!!) { token, _ ->
        if (token != null) {
          controller.updateHfToken(token)
        }
      }
    }
  }

  DisposableEffect(Unit) {
    onDispose { authManager.dispose() }
  }

  LaunchedEffect(Unit) { controller.restoreModel() }
  DisposableEffect(Unit) { onDispose { controller.close() } }
  val contextSnapshot = controller.contextSnapshot(prompt)
  val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
  var currentTab by remember { mutableStateOf(0) }

  Scaffold(
    modifier = Modifier.fillMaxSize().imePadding(),
    bottomBar = {
      NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        NavigationBarItem(
          selected = currentTab == 0,
          onClick = { currentTab = 0 },
          icon = { Icon(Icons.Rounded.Menu, "Chat") },
          label = { Text("Chat") }
        )
        NavigationBarItem(
          selected = currentTab == 1,
          onClick = { currentTab = 1 },
          icon = { Icon(Icons.Rounded.Menu, "Context") },
          label = { Text("Context") }
        )
        NavigationBarItem(
          selected = currentTab == 2,
          onClick = { currentTab = 2 },
          icon = { Icon(Icons.Rounded.Settings, "Settings") },
          label = { Text("Settings") }
        )
      }
    }
  ) { innerPadding ->
    Box(modifier = Modifier.fillMaxSize().padding(innerPadding).safeDrawingPadding()) {
      when (currentTab) {
        0 -> {
          Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          IconButton(onClick = { scope.launch { drawerState.open() } }) {
            Icon(Icons.Rounded.Menu, contentDescription = "Menu")
          }
          Text(
            "LLM Capability Runner",
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleLarge,
          )
        }
        ContextMeter(
          snapshot = contextSnapshot,
          onClick = { showContextManager = true },
          modifier = Modifier.width(180.dp),
        )
      }
    }

    ChatPanel(
      messages = controller.messages,
      compressedMessageIds = contextSnapshot.compressedEntryIds.toSet(),
      themeMode = controller.themeMode,
      modifier = Modifier.weight(1f).fillMaxWidth(),
      onKeep = { message ->
        pendingReview = message to ReviewDecision.KEEP
      },
      onReject = { message ->
        pendingReview = message to ReviewDecision.REJECT
      },
      onEdit = { message ->
        pendingReview = message to ReviewDecision.EDITED
      },
    )

    Text(
      controller.status,
      color = MaterialTheme.colorScheme.primary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      style = MaterialTheme.typography.labelSmall,
    )

    ContextPressureNotice(
      snapshot = contextSnapshot,
      onManage = { showContextManager = true },
      onCompress = { controller.compressContext(prompt) },
    )

    ComposerControlStrip(
      ready = controller.model != null && !controller.isBusy,
      enabled = !controller.isBusy,
      onMemories = { showMemoryDeck = true },
      onSkills = { showSkillLibrary = true },
      onReceipts = { showReceipts = true },
      onSettings = { showSettings = true },
      onClear = controller::newConversation,
    )

    val composerBorderColor = if (controller.model != null && !controller.isBusy) EdgeLightPalette.Gold else MaterialTheme.colorScheme.outline
    
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .border(2.dp, composerBorderColor, RoundedCornerShape(18.dp))
        .padding(4.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom
      ) {
        OutlinedTextField(
          value = prompt,
          onValueChange = { prompt = it },
          modifier = Modifier.weight(1f),
          placeholder = { Text("Ask Runner...", color = EdgeLightPalette.Gold) },
          enabled = !controller.isBusy && controller.model != null,
          minLines = 1,
          maxLines = 6,
          colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            disabledBorderColor = Color.Transparent,
          )
        )
        Row(
          modifier = Modifier.padding(bottom = 8.dp, end = 8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          IconButton(
            onClick = { importImage.launch(arrayOf("image/*")) },
            enabled = !controller.isBusy && controller.model != null && controller.settings.imageInputEnabled && controller.pendingImage == null
          ) {
            Icon(
              Icons.Rounded.AddCircle,
              contentDescription = "Add image",
              tint = if (controller.settings.imageInputEnabled) EdgeLightPalette.Cyan else Color.Gray
            )
          }
          if (controller.isBusy) {
            OutlinedButton(onClick = controller::cancel, shape = RoundedCornerShape(50)) {
              Text("Stop")
            }
          } else {
            GradientPillButton(
              text = "Send",
              onClick = {
                if (controller.send(prompt)) prompt = ""
              },
              enabled = (prompt.isNotBlank() || controller.pendingImage != null) && controller.model != null,
              contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            )
          }
        }
      }
    }
    controller.pendingImage?.let { image ->
      PendingImageCard(image = image, onRemove = controller::removePendingImage)
    }
          }
        }
        1 -> {
          com.terminus.edge.light.context.HistoryTabUi(
            sessionIds = controller.sessionIds,
            activeSessionId = controller.activeSessionId,
            onLoadConversation = { controller.loadConversation(it); currentTab = 0 },
            onNewConversation = { controller.newConversation(); currentTab = 0 },
            onManageMemories = { showMemoryDeck = true },
            onManageSkills = { showSkillLibrary = true },
            onViewReceipts = { showReceipts = true }
          )
        }
        2 -> {
          SettingsTabUi(
            themeMode = controller.themeMode,
            modelLabel = controller.model?.let { "${it.displayName} (${it.sizeBytes / 1_000_000} MB)" } ?: "No Model Loaded",
            isBusy = controller.isBusy,
            traceEnabled = controller.traceEnabled,
            traceStats = controller.traceStats,
            bubbleModeEnabled = BubbleOverlayService.mediaProjectionIntent != null,
            hfToken = controller.hfToken,
            geminiApiKey = controller.geminiApiKey,
            onThemeChange = controller::updateThemeMode,
            onImportModel = { importModel.launch(arrayOf("*/*")) },
            onDownloadModel = { showModelDownloader = true },
            onScanModels = {
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                  data = android.net.Uri.parse("package:${context.packageName}")
                }
                manageFilesLauncher.launch(intent)
              } else {
                scope.launch { controller.scanModels() }
              }
            },
            onTraceChange = { enabled -> controller.updateTraceEnabled(enabled, scope) },
            onExportRaw = { pendingExport = ExportMode.RAW },
            onExportCurated = { pendingExport = ExportMode.CURATED },
            onExportReplay = { pendingExport = ExportMode.REPLAY },
            onDeleteTraces = { showDeleteConfirmation = true },
            onShowStorage = { showStorageUi = true },
            onToggleBubbleMode = { enabled ->
              if (enabled) {
                if (!Settings.canDrawOverlays(context)) {
                  val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))
                  overlayPermissionLauncher.launch(intent)
                } else {
                  val projectionManager = context.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                  mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                }
              } else {
                context.startService(Intent(context, BubbleOverlayService::class.java).apply { action = "STOP" })
                BubbleOverlayService.mediaProjectionIntent = null
              }
            },
            onUpdateHfToken = { controller.updateHfToken(it) },
            onUpdateGeminiKey = { controller.updateGeminiApiKey(it) },
            onOpenSwarmMonitor = { showSwarmMonitor = true },
            onLoginHuggingFace = { authLauncher.launch(authManager.buildAuthIntent()) },
            controller = controller
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
              "Raw JSONL contains full prompts, responses, system prompts, and receipt metadata in plaintext."
            ExportMode.CURATED ->
              "Curated JSONL contains operator-approved training candidates in plaintext. Rights remain unverified."
            ExportMode.REPLAY ->
              "The replay ZIP contains full trace history, context snapshots, and the exact model binary. It may be several gigabytes."
          }
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            pendingExport = null
            when (mode) {
              ExportMode.RAW -> exportRaw.launch("runner-raw.jsonl")
              ExportMode.CURATED -> exportCurated.launch("runner-training.jsonl")
              ExportMode.REPLAY -> exportReplay.launch("runner-replay.zip")
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
      title = { Text("Delete all local traces?") },
      text = {
        Text(
          "This removes the private event ledger and its Skill, conversation, image, and model snapshots from this device."
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            controller.deleteTraces(scope)
            showDeleteConfirmation = false
          }
        ) {
          Text("Delete")
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

  if (showReceipts) {
    ReceiptsDialog(
      traceEnabled = controller.traceEnabled,
      traceStats = controller.traceStats,
      isBusy = controller.isBusy,
      modelLabel =
        controller.model?.let {
          "Model ${it.sha256.take(12)}... | ${formatBytes(it.sizeBytes)} " +
            modelCapabilityTag(it, controller.settings.imageInputEnabled)
        } ?: "No model imported",
      onDismiss = { showReceipts = false },
      onTraceChange = { enabled -> controller.updateTraceEnabled(enabled, scope) },
      onExportRaw = {
        showReceipts = false
        pendingExport = ExportMode.RAW
      },
      onExportCurated = {
        showReceipts = false
        pendingExport = ExportMode.CURATED
      },
      onExportReplay = {
        showReceipts = false
        pendingExport = ExportMode.REPLAY
      },
      onDeleteTraces = {
        showReceipts = false
        showDeleteConfirmation = true
      },
    )
  }


  if (showStorageUi) {
    StorageUiDialog(
      archiveSize = controller.archiveSize,
      blobStoreSize = controller.blobStoreSize,
      onClearArchives = controller::clearArchives,
      onClearBlobs = controller::clearBlobs,
      onDismiss = { showStorageUi = false }
    )
  }

  if (showSwarmMonitor) {
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    androidx.compose.material3.ModalBottomSheet(
      onDismissRequest = { showSwarmMonitor = false },
    ) {
      SwarmMonitorUi(
        agentStatesFlow = controller.agentStates,
        onDismiss = { showSwarmMonitor = false }
      )
    }
  }

  if (showModelDownloader) {
    ModelDownloaderDialog(
      onDismiss = { showModelDownloader = false },
      onModelDownloaded = { file ->
        showModelDownloader = false
        scope.launch { controller.importModel(android.net.Uri.fromFile(file)) }
      },
      downloadDir = File(androidx.compose.ui.platform.LocalContext.current.filesDir, "models"),
      hfToken = controller.hfToken,
      onTokenChanged = controller::updateHfToken,
      onSignInClicked = { authLauncher.launch(authManager.buildAuthIntent()) }
    )
  }

  if (controller.scannedModels.isNotEmpty()) {
    AlertDialog(
      onDismissRequest = { /* Cannot dismiss until user picks or cancels */ },
      title = { Text("Select Scanned Model") },
      text = {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 400.dp)
            .verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          controller.scannedModels.forEach { descriptor ->
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable {
                  scope.launch { controller.selectScannedModel(java.io.File(descriptor.path)) }
                }
                .padding(12.dp)
            ) {
              Column {
                Text(descriptor.displayName, fontWeight = FontWeight.SemiBold)
                Text(descriptor.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatBytes(descriptor.sizeBytes), style = MaterialTheme.typography.labelSmall)
              }
            }
          }
        }
      },
      confirmButton = {},
      dismissButton = {
        TextButton(onClick = { controller.cancelScan() }) {
          Text("Cancel")
        }
      }
    )
  }

  if (showContextManager) {
    ContextManagerSheet(
      snapshot = contextSnapshot,
      settings = controller.settings,
      contextSettings = controller.contextSettings,
      systemPrompt = controller.systemPrompt,
      isBusy = controller.isBusy,
      onDismiss = { showContextManager = false },
      onPolicyChange = controller::updateMessageRetention,
      onCompress = { controller.compressContext(prompt) },
      onRestore = controller::restoreCompressedContext,
      onClearTemporary = controller::clearTemporaryContext,
      onNewConversation = controller::newConversation,
      onApplyModelSettings = { settings, systemPrompt ->
        controller.updateModelSettings(settings, systemPrompt, scope)
      },
      onApplyContextSettings = controller::updateContextSettings,
    )
  }

  if (showMemoryDeck) {
    MemoryDeckDialog(
      memories = controller.memories,
      selectedIds = controller.selectedMemoryIds,
      onDismiss = { showMemoryDeck = false },
      onToggle = controller::toggleMemory,
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
  ready: Boolean,
  enabled: Boolean,
  onMemories: () -> Unit,
  onSkills: () -> Unit,
  onReceipts: () -> Unit,
  onSettings: () -> Unit,
  onClear: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(7.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      if (ready) "Ready" else "Waiting",
      color = MaterialTheme.colorScheme.primary,
      style = MaterialTheme.typography.labelMedium,
    )
    GradientPillButton(
      text = "Clear",
      onClick = onClear,
      enabled = enabled,
      contentPadding = PaddingValues(horizontal = 11.dp, vertical = 7.dp),
    )
    GradientPillButton(
      text = "Memories",
      onClick = onMemories,
      enabled = enabled,
      contentPadding = PaddingValues(horizontal = 11.dp, vertical = 7.dp),
    )
    GradientPillButton(
      text = "Skills",
      onClick = onSkills,
      enabled = enabled,
      contentPadding = PaddingValues(horizontal = 11.dp, vertical = 7.dp),
    )
    GradientPillButton(
      text = "Receipts",
      onClick = onReceipts,
      enabled = enabled,
      contentPadding = PaddingValues(horizontal = 11.dp, vertical = 7.dp),
    )
    Spacer(Modifier.weight(1f))
    Text(
      "Message",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelMedium,
    )
    Box(
      modifier =
        Modifier.size(38.dp)
          .clip(CircleShape)
          .then(
            if (LocalEdgeThemeMode.current == EdgeThemeMode.DEFAULT) {
              Modifier.background(EdgeLightPalette.Gradient)
            } else {
              Modifier.background(MaterialTheme.colorScheme.primary)
            }
          )
          .clickable(enabled = enabled, role = Role.Button, onClick = onSettings),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Rounded.Settings,
        contentDescription = "Settings",
        tint =
          if (LocalEdgeThemeMode.current == EdgeThemeMode.DEFAULT) {
            Color.White
          } else {
            MaterialTheme.colorScheme.onPrimary
          },
        modifier = Modifier.size(20.dp),
      )
    }
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
  val listState = androidx.compose.foundation.lazy.rememberLazyListState()
  LaunchedEffect(messages.size) {
    if (messages.isNotEmpty()) {
      listState.animateScrollToItem(messages.lastIndex)
    }
  }

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
        state = listState,
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
      MessageRole.USER -> EdgeLightPalette.Gold
      MessageRole.ASSISTANT -> EdgeLightPalette.DeepPurple
      MessageRole.ERROR -> EdgeLightPalette.Cyan
    }
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    colors = CardDefaults.cardColors(containerColor = background),
    border = edgeLightBorder(),
  ) {
    Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
      Text(
        when (message.role) {
          MessageRole.USER -> "Operator"
          MessageRole.ASSISTANT -> "Agent"
          MessageRole.ERROR -> "System"
        },
        color = contentColor,
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
            val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
            TextButton(onClick = { clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(message.content)) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
              Text("Copy")
            }
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

private fun modelCapabilityTag(model: ModelDescriptor, imageInputEnabled: Boolean): String {
  val knownVisionModel =
    model.displayName.contains("gemma-4", ignoreCase = true) ||
      model.displayName.contains("gemma-3n", ignoreCase = true)
  return if (imageInputEnabled || knownVisionModel) "[Vision]" else "[Text]"
}
