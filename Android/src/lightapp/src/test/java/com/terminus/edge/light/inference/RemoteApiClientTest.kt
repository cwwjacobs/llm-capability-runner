package com.terminus.edge.light.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteApiClientTest {
  private val client = RemoteApiClient()

  @Test
  fun buildsProviderRequestsWithoutCredentials() {
    val settings = GenerationSettings(maxTokens = 4096)
    val gemini =
      client.buildGeminiRequest(
        systemPrompt = "system",
        prompt = "hello",
        imageBytes = listOf(byteArrayOf(1, 2, 3)),
        settings = settings,
      )
    val deepSeek =
      client.buildDeepSeekRequest(
        model = "deepseek-v4-flash",
        systemPrompt = "system",
        prompt = "hello",
        settings = settings,
      )

    assertTrue(gemini.toString().contains("inline_data"))
    assertEquals("deepseek-v4-flash", deepSeek.getString("model"))
    assertFalse(gemini.toString().contains("api_key", ignoreCase = true))
    assertFalse(deepSeek.toString().contains("api_key", ignoreCase = true))
  }

  @Test
  fun parsesFinalProviderTextAndOmitsGeminiThoughtParts() {
    val gemini =
      """{"candidates":[{"content":{"parts":[{"text":"hidden","thought":true},{"text":"answer"}]}}]}"""
    val deepSeek =
      """{"choices":[{"message":{"content":"done","reasoning_content":"hidden"}}]}"""

    assertEquals("answer", client.parseGeminiResponse(gemini))
    assertEquals("done", client.parseDeepSeekResponse(deepSeek))
  }
}
