package com.terminus.edge.light.storage

import java.io.File
import java.util.zip.GZIPOutputStream

object StorageCompactor {
  fun compact(sourceLog: File, archiveDir: File, thresholdBytes: Long = 5 * 1024 * 1024) {
    if (!sourceLog.exists() || sourceLog.length() < thresholdBytes) return
    val timestamp = System.currentTimeMillis()
    val archiveFile = File(archiveDir, "ledger-$timestamp.jsonl.gz")
    
    if (!archiveDir.exists()) archiveDir.mkdirs()
    
    sourceLog.inputStream().use { input ->
      GZIPOutputStream(archiveFile.outputStream()).use { output ->
        input.copyTo(output)
      }
    }
    
    // Clear the source log since it's archived
    sourceLog.writeText("")
  }

  fun getArchiveSize(archiveDir: File): Long =
    if (archiveDir.exists()) archiveDir.walk().filter { it.isFile }.sumOf { it.length() } else 0

  fun clearArchives(archiveDir: File) {
    if (archiveDir.exists()) archiveDir.listFiles()?.forEach { it.delete() }
  }
}
