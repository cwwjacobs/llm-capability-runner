package com.terminus.edge.light.storage

import com.terminus.edge.light.trace.TraceIntegrity
import java.io.File

class BlobStore(private val root: File) {
  init {
    require(root.mkdirs() || root.isDirectory) { "Could not create the Blob store." }
  }
  
  fun save(bytes: ByteArray): String {
    val sha256 = TraceIntegrity.sha256(bytes)
    val file = File(root, "$sha256.blob")
    if (!file.exists()) {
      file.writeBytes(bytes)
    }
    return sha256
  }
  
  fun load(sha256: String): ByteArray? {
    val file = File(root, "$sha256.blob")
    return if (file.exists()) file.readBytes() else null
  }

  fun getStoreSize(): Long =
    root.walk().filter { it.isFile }.sumOf { it.length() }

  fun clearAll() {
    root.listFiles()?.forEach { it.delete() }
  }
}
