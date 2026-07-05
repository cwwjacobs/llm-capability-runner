package com.terminus.edge.light.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import com.terminus.edge.light.inference.AgentRole

data class ModelDescriptor(
  val displayName: String,
  val path: String,
  val sha256: String,
  val sizeBytes: Long,
  val importedAtMs: Long,
)

data class StagedModel(
  val descriptor: ModelDescriptor,
  val file: File,
)

class ModelStore(private val context: Context) {
  private val preferences = context.getSharedPreferences("edge_light_model", Context.MODE_PRIVATE)
  private val modelDirectory = File(context.filesDir, "models")
  private val modelFile = File(modelDirectory, "model.litertlm")

  fun current(role: AgentRole = AgentRole.ORCHESTRATOR): ModelDescriptor? {
    val suffix = if (role == AgentRole.ORCHESTRATOR) "" else "_${role.name}"
    val defaultPath = if (role == AgentRole.ORCHESTRATOR) modelFile.absolutePath else null
    val path = preferences.getString(KEY_PATH + suffix, defaultPath) ?: return null
    val file = File(path)
    if (!file.isFile || file.length() == 0L) return null
    val name = preferences.getString(KEY_NAME + suffix, null) ?: return null
    val sha256 = preferences.getString(KEY_SHA256 + suffix, null) ?: return null
    return ModelDescriptor(
      displayName = name,
      path = file.absolutePath,
      sha256 = sha256,
      sizeBytes = file.length(),
      importedAtMs = preferences.getLong(KEY_IMPORTED_AT + suffix, 0L),
    )
  }

  fun scanStorage(): List<ModelDescriptor> {
    val root = File("/storage/emulated/0")
    if (!root.exists() || !root.isDirectory) return emptyList()

    val foundModels = mutableListOf<ModelDescriptor>()
    root.walkTopDown().forEach { file ->
      if (file.isFile && (file.name.endsWith(".litertlm") || file.name.endsWith(".tflite") || file.name.endsWith(".bin") || file.name.endsWith(".task"))) {
        foundModels.add(
          ModelDescriptor(
            displayName = file.name,
            path = file.absolutePath,
            sha256 = "unknown", // SHA-256 calculation for large files is skipped during scan for speed
            sizeBytes = file.length(),
            importedAtMs = file.lastModified()
          )
        )
      }
    }
    return foundModels
  }

  fun commitExternal(file: File, role: AgentRole = AgentRole.ORCHESTRATOR): ModelDescriptor {
    require(file.isFile && file.length() > 0L) { "Selected file is invalid or empty." }
    
    // Compute SHA-256 lazily upon selection
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    file.inputStream().use { input ->
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count > 0) digest.update(buffer, 0, count)
      }
    }
    val sha256Str = digest.digest().joinToString("") { byte -> "%02x".format(byte) }

    val descriptor = ModelDescriptor(
      displayName = file.name,
      path = file.absolutePath,
      sha256 = sha256Str,
      sizeBytes = file.length(),
      importedAtMs = System.currentTimeMillis()
    )

    val suffix = if (role == AgentRole.ORCHESTRATOR) "" else "_${role.name}"
    val saved =
      preferences
      .edit()
      .putString(KEY_NAME + suffix, descriptor.displayName)
      .putString(KEY_PATH + suffix, descriptor.path)
      .putString(KEY_SHA256 + suffix, descriptor.sha256)
      .putLong(KEY_IMPORTED_AT + suffix, descriptor.importedAtMs)
      .commit()
    require(saved) { "The model metadata could not be saved." }
    return descriptor
  }

  fun stage(uri: Uri): StagedModel {
    modelDirectory.mkdirs()
    val tempFile = File(modelDirectory, "model.importing")
    tempFile.delete()
    val digest = MessageDigest.getInstance("SHA-256")
    val displayName = queryDisplayName(uri) ?: "Imported model"

    try {
      context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "Unable to open the selected model." }
        FileOutputStream(tempFile, false).use { output ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
          }
          output.fd.sync()
        }
      }

      require(tempFile.length() > 0L) { "The selected model is empty." }
      return StagedModel(
        descriptor =
          ModelDescriptor(
            displayName = displayName,
            path = tempFile.absolutePath,
            sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) },
            sizeBytes = tempFile.length(),
            importedAtMs = System.currentTimeMillis(),
          ),
        file = tempFile,
      )
    } catch (error: Throwable) {
      tempFile.delete()
      throw error
    }
  }

  fun commit(staged: StagedModel, role: AgentRole = AgentRole.ORCHESTRATOR): ModelDescriptor {
    require(staged.file.canonicalFile == File(modelDirectory, "model.importing").canonicalFile) {
      "Invalid staged model path."
    }
    require(staged.file.isFile && staged.file.length() == staged.descriptor.sizeBytes) {
      "The staged model is unavailable or changed."
    }
    
    val targetFile = if (role == AgentRole.ORCHESTRATOR) modelFile else File(modelDirectory, "model_${role.name}.litertlm")
    moveIntoPlace(staged.file, targetFile)
    val descriptor =
      staged.descriptor.copy(path = targetFile.absolutePath, sizeBytes = targetFile.length())
      
    val suffix = if (role == AgentRole.ORCHESTRATOR) "" else "_${role.name}"
    val saved =
      preferences
      .edit()
      .putString(KEY_NAME + suffix, descriptor.displayName)
      .putString(KEY_PATH + suffix, descriptor.path)
      .putString(KEY_SHA256 + suffix, descriptor.sha256)
      .putLong(KEY_IMPORTED_AT + suffix, descriptor.importedAtMs)
      .commit()
    require(saved) { "The imported model metadata could not be saved." }
    return descriptor
  }

  fun discard(staged: StagedModel) {
    if (staged.file.name == "model.importing") staged.file.delete()
  }

  private fun queryDisplayName(uri: Uri): String? {
    return context.contentResolver
      .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
      ?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        cursor.getString(0)?.take(160)
      }
  }

  private fun moveIntoPlace(source: File, target: File) {
    try {
      Files.move(
        source.toPath(),
        target.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    } catch (_: Exception) {
      Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
  }

  private companion object {
    const val KEY_NAME = "name"
    const val KEY_PATH = "path"
    const val KEY_SHA256 = "sha256"
    const val KEY_IMPORTED_AT = "imported_at"
  }
}
