package com.terminus.edge.light.model

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelStoreTest {
  @Test
  fun devicePickerOnlyAcceptsRunnableRuntimeFormats() {
    val gguf = sizedTempFile("model", ".gguf")
    val litertLm = sizedTempFile("model", ".litertlm")
    assertTrue(isRunnableModelFile(gguf))
    assertTrue(isRunnableModelFile(litertLm))
    assertFalse(isRunnableModelFile(File("mmproj-model.gguf")))
    assertFalse(isRunnableModelFile(File("vision-encoder.gguf")))
    assertFalse(isRunnableModelFile(File("model.task")))
    assertFalse(isRunnableModelFile(File("model.tflite")))
    assertFalse(isRunnableModelFile(File("pytorch_model.bin")))
  }

  private fun sizedTempFile(prefix: String, suffix: String): File =
    kotlin.io.path.createTempFile(prefix, suffix).toFile().apply {
      deleteOnExit()
      RandomAccessFile(this, "rw").use { it.setLength(MIN_RUNNABLE_MODEL_BYTES) }
    }
}
