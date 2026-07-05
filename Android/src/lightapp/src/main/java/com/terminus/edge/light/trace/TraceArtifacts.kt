package com.terminus.edge.light.trace

import com.terminus.edge.light.model.ModelDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class TraceArtifactRef(
  val kind: String,
  val logicalId: String?,
  val sha256: String,
  val sizeBytes: Long,
  val mediaType: String,
  val storagePath: String,
  val fingerprint: String? = null,
)

data class ModelSnapshot(
  val displayName: String,
  val sha256: String,
  val sizeBytes: Long,
  val importedAtMs: Long,
  val artifact: TraceArtifactRef? = null,
)

class TraceArtifactStore(private val root: File) {
  private val verifiedHashes = mutableSetOf<String>()

  init {
    require(root.mkdirs() || root.isDirectory) { "Could not create the trace artifact store." }
  }

  fun snapshotText(
    kind: String,
    logicalId: String?,
    extension: String,
    mediaType: String,
    content: String,
    fingerprint: String? = null,
  ): TraceArtifactRef {
    return snapshotBytes(
      kind = kind,
      logicalId = logicalId,
      extension = extension,
      mediaType = mediaType,
      bytes = content.toByteArray(Charsets.UTF_8),
      fingerprint = fingerprint,
    )
  }

  fun snapshotBytes(
    kind: String,
    logicalId: String?,
    extension: String,
    mediaType: String,
    bytes: ByteArray,
    fingerprint: String? = null,
  ): TraceArtifactRef {
    val sha256 = TraceIntegrity.sha256(bytes)
    val relativePath = "$kind/$sha256.${extension.trimStart('.')}"
    val target = resolveStoragePath(relativePath)
    writeOnce(target, bytes)
    return TraceArtifactRef(
      kind = kind,
      logicalId = logicalId,
      sha256 = sha256,
      sizeBytes = bytes.size.toLong(),
      mediaType = mediaType,
      storagePath = relativePath,
      fingerprint = fingerprint,
    )
  }

  fun describeModel(model: ModelDescriptor): ModelSnapshot {
    val source = File(model.path)
    require(source.isFile && source.length() == model.sizeBytes) {
      "The active model file is unavailable or changed."
    }
    return ModelSnapshot(
      displayName = model.displayName,
      sha256 = model.sha256,
      sizeBytes = model.sizeBytes,
      importedAtMs = model.importedAtMs,
    )
  }

  @Suppress("UsableSpace")
  fun snapshotModel(model: ModelDescriptor): ModelSnapshot {
    val source = File(model.path)
    require(source.isFile && source.length() == model.sizeBytes) {
      "The active model file is unavailable or changed."
    }
    val relativePath = "models/${model.sha256}.litertlm"
    val target = resolveStoragePath(relativePath)
    verifyHashOnce(source, model.sha256, "The active model SHA-256 does not match its descriptor.")
    if (!target.exists()) {
      target.parentFile?.mkdirs()
      val temp = File(target.parentFile, ".${target.name}.snapshotting")
      runCatching { temp.delete() }
      try {
        runCatching { Files.createLink(temp.toPath(), source.toPath()) }
          .getOrElse {
            require(root.usableSpace > source.length() + COPY_SAFETY_MARGIN_BYTES) {
              "Not enough free space to preserve the replay model snapshot."
            }
            copyAndSync(source, temp)
          }
        require(temp.length() == model.sizeBytes) { "The model snapshot size does not match." }
        moveIntoPlace(temp, target)
      } finally {
        if (temp.exists()) temp.delete()
      }
    }
    require(target.isFile && target.length() == model.sizeBytes) {
      "The stored model snapshot is incomplete."
    }
    verifyHashOnce(target, model.sha256, "The stored model snapshot SHA-256 does not match.")
    return ModelSnapshot(
      displayName = model.displayName,
      sha256 = model.sha256,
      sizeBytes = model.sizeBytes,
      importedAtMs = model.importedAtMs,
      artifact =
        TraceArtifactRef(
          kind = "models",
          logicalId = model.displayName,
          sha256 = model.sha256,
          sizeBytes = model.sizeBytes,
          mediaType = "application/octet-stream",
          storagePath = relativePath,
        ),
    )
  }

  fun resolve(reference: TraceArtifactRef): File {
    val file = resolveStoragePath(reference.storagePath)
    require(file.isFile) { "Missing trace artifact: ${reference.storagePath}" }
    require(file.length() == reference.sizeBytes) {
      "Trace artifact size mismatch: ${reference.storagePath}"
    }
    return file
  }

  fun deleteAll(): Boolean = !root.exists() || root.deleteRecursively()

  private fun writeOnce(target: File, bytes: ByteArray) {
    if (target.isFile) {
      require(target.length() == bytes.size.toLong()) {
        "Existing trace artifact has an unexpected size."
      }
      verifyHashOnce(
        target,
        TraceIntegrity.sha256(bytes),
        "Existing trace artifact has an unexpected SHA-256.",
      )
      return
    }
    target.parentFile?.mkdirs()
    val temp = File(target.parentFile, ".${target.name}.writing")
    try {
      FileOutputStream(temp, false).use { output ->
        output.write(bytes)
        output.fd.sync()
      }
      moveIntoPlace(temp, target)
    } finally {
      if (temp.exists()) temp.delete()
    }
  }

  private fun copyAndSync(source: File, target: File) {
    FileInputStream(source).use { input ->
      FileOutputStream(target, false).use { output ->
        input.copyTo(output)
        output.fd.sync()
      }
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

  private fun resolveStoragePath(relativePath: String): File {
    require(relativePath.isNotBlank() && !relativePath.startsWith('/')) {
      "Trace artifact paths must be relative."
    }
    val base = root.canonicalFile
    val target = File(base, relativePath).canonicalFile
    require(target.toPath().startsWith(base.toPath())) { "Trace artifact path escapes its store." }
    return target
  }

  private fun verifyHashOnce(file: File, expectedSha256: String, message: String) {
    val key = "${file.canonicalPath}:$expectedSha256:${file.length()}:${file.lastModified()}"
    synchronized(verifiedHashes) {
      if (key in verifiedHashes) return
      require(TraceIntegrity.sha256(file) == expectedSha256) { message }
      verifiedHashes += key
    }
  }

  private companion object {
    const val COPY_SAFETY_MARGIN_BYTES = 64L * 1024L * 1024L
  }
}

object TraceIntegrity {
  fun sha256(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

  fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

  fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count > 0) digest.update(buffer, 0, count)
      }
    }
    return digest.digest().toHex()
  }

  private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
}
