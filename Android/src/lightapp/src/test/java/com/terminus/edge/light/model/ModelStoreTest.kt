package com.terminus.edge.light.model

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelStoreTest {
  @Test
  fun devicePickerOnlyAcceptsRunnableRuntimeFormats() {
    assertTrue(isRunnableModelFile(File("model.gguf")))
    assertTrue(isRunnableModelFile(File("model.litertlm")))
    assertFalse(isRunnableModelFile(File("mmproj-model.gguf")))
    assertFalse(isRunnableModelFile(File("model.task")))
    assertFalse(isRunnableModelFile(File("model.tflite")))
    assertFalse(isRunnableModelFile(File("pytorch_model.bin")))
  }
}
