package com.terminus.edge.light.model

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class GgufMetadataProbeTest {
  @Test
  fun readsCoreMetadata() {
    val file = Files.createTempFile("metadata", ".gguf").toFile()
    val bytes = ByteArrayOutputStream()
    bytes.write("GGUF".toByteArray())
    bytes.little(3, 4)
    bytes.little(0, 8)
    bytes.little(3, 8)
    bytes.string("general.architecture")
    bytes.little(8, 4)
    bytes.string("llama")
    bytes.string("general.file_type")
    bytes.little(4, 4)
    bytes.little(15, 4)
    bytes.string("llama.context_length")
    bytes.little(4, 4)
    bytes.little(8192, 4)
    file.writeBytes(bytes.toByteArray())

    val metadata = GgufMetadataProbe.read(file)
    assertEquals("llama", metadata.architecture)
    assertEquals("Q4_K_M", metadata.quantization)
    assertEquals(8192, metadata.contextTokens)
  }

  private fun ByteArrayOutputStream.string(value: String) {
    val encoded = value.toByteArray()
    little(encoded.size.toLong(), 8)
    write(encoded)
  }

  private fun ByteArrayOutputStream.little(value: Long, bytes: Int) {
    repeat(bytes) { write(((value ushr (it * 8)) and 0xff).toInt()) }
  }
}
