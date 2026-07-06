package com.terminus.edge.light.model

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream

data class GgufMetadataSummary(
  val architecture: String?,
  val quantization: String?,
  val contextTokens: Int?,
)

object GgufMetadataProbe {
  fun read(file: File): GgufMetadataSummary {
    require(file.isFile && file.extension.equals("gguf", true)) { "Not a GGUF file." }
    BufferedInputStream(file.inputStream(), 256 * 1024).use { input ->
      require(String(input.readExact(4), Charsets.US_ASCII) == "GGUF") { "Invalid GGUF header." }
      val version = input.u32().toInt()
      require(version in 2..3) { "Unsupported GGUF version $version." }
      input.u64() // tensor count
      val metadataCount = input.u64()
      require(metadataCount in 1..1_000_000) { "Invalid GGUF metadata count." }
      var architecture: String? = null
      var fileType: Int? = null
      var contextTokens: Int? = null
      repeat(metadataCount.toInt()) {
        val key = input.ggufString(maxBytes = 16 * 1024)
        val type = input.u32().toInt()
        val wanted =
          key == "general.architecture" ||
            key == "general.file_type" ||
            (architecture != null && key == "$architecture.context_length")
        val value = if (wanted) input.readValue(type) else input.skipValue(type)
        when (key) {
          "general.architecture" -> architecture = value as? String
          "general.file_type" -> fileType = (value as? Number)?.toInt()
          "${architecture}.context_length" -> contextTokens = (value as? Number)?.toInt()
        }
        if (architecture != null && fileType != null && contextTokens != null) {
          return GgufMetadataSummary(architecture, quantizationLabel(fileType), contextTokens)
        }
      }
      return GgufMetadataSummary(architecture, quantizationLabel(fileType), contextTokens)
    }
  }

  private fun InputStream.readValue(type: Int): Any? =
    when (type) {
      0, 1 -> readByteChecked()
      2, 3 -> readLittle(2)
      4, 5 -> u32()
      6 -> Float.fromBits(u32().toInt())
      7 -> readByteChecked() != 0
      8 -> ggufString(maxBytes = 1024 * 1024)
      10, 11 -> u64()
      12 -> Double.fromBits(u64())
      else -> {
        skipValue(type)
        null
      }
    }

  private fun InputStream.skipValue(type: Int): Any? {
    when (type) {
      0, 1, 7 -> skipExact(1)
      2, 3 -> skipExact(2)
      4, 5, 6 -> skipExact(4)
      8 -> skipExact(u64())
      9 -> {
        val itemType = u32().toInt()
        val count = u64()
        require(count in 0..50_000_000) { "GGUF array is too large." }
        repeat(count.toInt()) { skipValue(itemType) }
      }
      10, 11, 12 -> skipExact(8)
      else -> error("Unknown GGUF metadata type $type.")
    }
    return null
  }

  private fun InputStream.ggufString(maxBytes: Int): String {
    val length = u64()
    require(length in 0..maxBytes.toLong()) { "GGUF string is too large." }
    return String(readExact(length.toInt()), Charsets.UTF_8)
  }

  private fun InputStream.u32(): Long = readLittle(4)

  private fun InputStream.u64(): Long = readLittle(8)

  private fun InputStream.readLittle(bytes: Int): Long {
    val value = readExact(bytes)
    var result = 0L
    for (index in value.indices.reversed()) {
      result = (result shl 8) or (value[index].toLong() and 0xffL)
    }
    return result
  }

  private fun InputStream.readByteChecked(): Int {
    val value = read()
    if (value < 0) throw EOFException("Unexpected end of GGUF file.")
    return value
  }

  private fun InputStream.readExact(count: Int): ByteArray {
    val output = ByteArray(count)
    var offset = 0
    while (offset < count) {
      val read = read(output, offset, count - offset)
      if (read < 0) throw EOFException("Unexpected end of GGUF file.")
      offset += read
    }
    return output
  }

  private fun InputStream.skipExact(count: Long) {
    var remaining = count
    while (remaining > 0) {
      val skipped = skip(remaining)
      if (skipped > 0) {
        remaining -= skipped
      } else {
        if (read() < 0) throw EOFException("Unexpected end of GGUF file.")
        remaining -= 1
      }
    }
  }

  private fun quantizationLabel(fileType: Int?): String? =
    when (fileType) {
      0 -> "F32"
      1 -> "F16"
      2 -> "Q4_0"
      3 -> "Q4_1"
      7 -> "Q8_0"
      8 -> "Q5_0"
      9 -> "Q5_1"
      10 -> "Q2_K"
      11 -> "Q3_K_S"
      12 -> "Q3_K_M"
      13 -> "Q3_K_L"
      14 -> "Q4_K_S"
      15 -> "Q4_K_M"
      16 -> "Q5_K_S"
      17 -> "Q5_K_M"
      18 -> "Q6_K"
      else -> fileType?.let { "type-$it" }
    }
}
