package com.terminus.edge.light.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HuggingFaceClientTest {
  @Test
  fun acceptsRunnableLocalBundles() {
    assertTrue(HuggingFaceClient.isCompatibleModelFile("models/gemma.litertlm"))
    assertTrue(HuggingFaceClient.isCompatibleModelFile("GEMMA.LITERTLM"))
    assertTrue(HuggingFaceClient.isCompatibleModelFile("gemma-q4_k_m.gguf"))
    assertTrue(HuggingFaceClient.isCompatibleModelFile("mmproj-model.gguf"))
    assertFalse(HfModelFile("mmproj-model.gguf", 200L * 1024L * 1024L).isPrimaryChoice)
    assertFalse(HfModelFile("vision-encoder.gguf", 200L * 1024L * 1024L).isPrimaryChoice)
    assertFalse(HfModelFile("tiny.gguf", 37L * 1024L * 1024L).isPrimaryChoice)
    assertFalse(HuggingFaceClient.isCompatibleModelFile("pytorch_model.bin"))
    assertFalse(HuggingFaceClient.isCompatibleModelFile("model.task"))
  }

  @Test
  fun groupsSplitGgufAndFindsProjector() {
    val files =
      listOf(
        HfModelFile("model-Q4_K_M-00001-of-00002.gguf", 10),
        HfModelFile("model-Q4_K_M-00002-of-00002.gguf", 11),
        HfModelFile("mmproj-model-Q4_K_M.gguf", 3),
      )
    assertEquals(2, HuggingFaceClient.requiredFiles(files.first(), files).size)
    assertEquals(files.last(), HuggingFaceClient.companionProjector(files.first(), files))
  }

  @Test
  fun sanitizesDownloadedFileNames() {
    assertEquals(
      "model_name.litertlm",
      HuggingFaceClient.safeFileName("nested/model name.litertlm"),
    )
  }

  @Test
  fun summarizesParametersAndDownloadSizesForCompactCards() {
    assertEquals("12B", HuggingFaceClient.parameterLabel("owner/gemma-4-12B-it-GGUF"))
    assertEquals("1.5B", HuggingFaceClient.parameterLabel("owner/model-1.5b-instruct"))
    assertEquals("E2B", HuggingFaceClient.parameterLabel("owner/gemma-4-E2B-it-litert-lm"))

    val files =
      listOf(
        HfModelFile("model-Q4_K_M.gguf", 400L * 1024L * 1024L),
        HfModelFile("model-Q8_0.gguf", 800L * 1024L * 1024L),
      )
    assertEquals(
      400L * 1024L * 1024L to 800L * 1024L * 1024L,
      HuggingFaceClient.downloadableSizeRange(files),
    )
  }
}
