package com.terminus.edge.light.inference

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class InferenceBackend(val wireValue: String, val label: String) {
  LOCAL("local", "On-device"),
  GEMINI("gemini", "Gemini API"),
  DEEPSEEK("deepseek", "DeepSeek API");

  companion object {
    fun fromWireValue(value: String?): InferenceBackend =
      entries.firstOrNull { it.wireValue == value } ?: LOCAL
  }
}

data class ApiProviderConfiguration(
  val backend: InferenceBackend,
  val geminiModel: String,
  val deepSeekModel: String,
)

class RemoteApiClient {
  @Volatile private var activeConnection: HttpURLConnection? = null
  private val cancelled = AtomicBoolean(false)

  suspend fun generate(
    backend: InferenceBackend,
    apiKey: String,
    model: String,
    systemPrompt: String,
    prompt: String,
    imageBytes: List<ByteArray>,
    settings: GenerationSettings,
    onChunk: (String) -> Unit,
  ): String =
    withContext(Dispatchers.IO) {
      require(apiKey.isNotBlank()) { "${backend.label} key is not configured." }
      require(model.matches(MODEL_ID)) { "The API model ID is invalid." }
      if (backend == InferenceBackend.DEEPSEEK && imageBytes.isNotEmpty()) {
        error("DeepSeek API is configured for text-only requests in this app.")
      }
      cancelled.set(false)
      val connection =
        when (backend) {
          InferenceBackend.GEMINI ->
            URL(
                "$GEMINI_BASE/models/${encodePath(model)}:generateContent"
              )
              .openConnection() as HttpURLConnection
          InferenceBackend.DEEPSEEK ->
            URL("$DEEPSEEK_BASE/chat/completions").openConnection() as HttpURLConnection
          InferenceBackend.LOCAL -> error("Local inference does not use the remote API client.")
        }
      activeConnection = connection
      try {
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 20_000
        connection.readTimeout = 180_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("User-Agent", "Edge-Light/0.7 (Android)")
        when (backend) {
          InferenceBackend.GEMINI -> connection.setRequestProperty("x-goog-api-key", apiKey)
          InferenceBackend.DEEPSEEK ->
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
          InferenceBackend.LOCAL -> Unit
        }
        val request =
          when (backend) {
            InferenceBackend.GEMINI ->
              buildGeminiRequest(systemPrompt, prompt, imageBytes, settings)
            InferenceBackend.DEEPSEEK ->
              buildDeepSeekRequest(model, systemPrompt, prompt, settings)
            InferenceBackend.LOCAL -> error("Unsupported backend.")
          }
        connection.outputStream.use { output ->
          output.write(request.toString().toByteArray(StandardCharsets.UTF_8))
          output.flush()
        }
        val status = connection.responseCode
        if (status !in 200..299) {
          val message =
            connection.errorStream
              ?.bufferedReader()
              ?.use { it.readText().take(16_000) }
              ?.let(::remoteErrorMessage)
          error(message ?: "${backend.label} request failed with HTTP $status.")
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val response =
          when (backend) {
            InferenceBackend.GEMINI -> parseGeminiResponse(body)
            InferenceBackend.DEEPSEEK -> parseDeepSeekResponse(body)
            InferenceBackend.LOCAL -> error("Unsupported backend.")
          }
        require(response.isNotBlank()) { "${backend.label} returned an empty response." }
        onChunk(response)
        response
      } catch (error: Throwable) {
        if (cancelled.get()) throw CancellationException("Remote generation stopped.")
        throw error
      } finally {
        activeConnection?.disconnect()
        activeConnection = null
      }
    }

  fun cancel() {
    cancelled.set(true)
    activeConnection?.disconnect()
  }

  internal fun buildGeminiRequest(
    systemPrompt: String,
    prompt: String,
    imageBytes: List<ByteArray>,
    settings: GenerationSettings,
  ): JSONObject =
    JSONObject()
      .put(
        "system_instruction",
        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))),
      )
      .put(
        "contents",
        JSONArray()
          .put(
            JSONObject()
              .put("role", "user")
              .put(
                "parts",
                JSONArray()
                  .put(JSONObject().put("text", prompt))
                  .apply {
                    imageBytes.forEach { bytes ->
                      put(
                        JSONObject()
                          .put(
                            "inline_data",
                            JSONObject()
                              .put("mime_type", "image/png")
                              .put("data", Base64.getEncoder().encodeToString(bytes)),
                          )
                      )
                    }
                  },
              ),
          ),
      )
      .put(
        "generationConfig",
        JSONObject()
          .put("maxOutputTokens", outputTokens(settings))
          .put("temperature", settings.temperature)
          .put("topP", settings.topP)
          .put("topK", settings.topK),
      )

  internal fun buildDeepSeekRequest(
    model: String,
    systemPrompt: String,
    prompt: String,
    settings: GenerationSettings,
  ): JSONObject =
    JSONObject()
      .put("model", model)
      .put(
        "messages",
        JSONArray()
          .put(JSONObject().put("role", "system").put("content", systemPrompt))
          .put(JSONObject().put("role", "user").put("content", prompt)),
      )
      .put("max_tokens", outputTokens(settings))
      .put("temperature", settings.temperature)
      .put("top_p", settings.topP)
      .put("stream", false)

  internal fun parseGeminiResponse(body: String): String {
    val parts =
      JSONObject(body)
        .optJSONArray("candidates")
        ?.optJSONObject(0)
        ?.optJSONObject("content")
        ?.optJSONArray("parts")
        ?: return ""
    return buildString {
        for (index in 0 until parts.length()) {
          val part = parts.optJSONObject(index) ?: continue
          if (!part.optBoolean("thought", false)) append(part.optString("text"))
        }
      }
      .trim()
  }

  internal fun parseDeepSeekResponse(body: String): String =
    JSONObject(body)
      .optJSONArray("choices")
      ?.optJSONObject(0)
      ?.optJSONObject("message")
      ?.optString("content")
      .orEmpty()
      .trim()

  private fun remoteErrorMessage(body: String): String? =
    runCatching {
        val json = JSONObject(body)
        val error = json.optJSONObject("error")
        (error?.optString("message") ?: json.optString("message"))
          .takeIf(String::isNotBlank)
          ?.take(240)
      }
      .getOrNull()

  private fun outputTokens(settings: GenerationSettings): Int =
    (settings.maxTokens / 4).coerceIn(128, 8_192)

  private fun encodePath(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

  private companion object {
    const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta"
    const val DEEPSEEK_BASE = "https://api.deepseek.com"
    val MODEL_ID = Regex("[A-Za-z0-9._:/-]{1,160}")
  }
}
