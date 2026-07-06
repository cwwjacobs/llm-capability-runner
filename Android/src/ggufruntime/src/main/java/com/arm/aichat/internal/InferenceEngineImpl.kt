package com.arm.aichat.internal

import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Narrow Kotlin/JNI bridge over the pinned llama.cpp Android sample.
 * Native symbol names intentionally match the upstream binding.
 */
class InferenceEngineImpl(context: Context) : AutoCloseable {
  private val nativeLibDir = context.applicationInfo.nativeLibraryDir
  private val mutex = Mutex()
  @Volatile private var initialized = false
  @Volatile private var loaded = false

  private external fun init(nativeLibDir: String)
  private external fun load(modelPath: String): Int
  private external fun prepare(): Int
  private external fun processSystemPrompt(systemPrompt: String): Int
  private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int
  private external fun loadProjector(projectorPath: String): Int
  private external fun unloadProjector()
  private external fun processImagePrompt(
    userPrompt: String,
    imageBytes: ByteArray,
    predictLength: Int,
  ): Int
  private external fun generateNextToken(): String?
  private external fun unload()
  private external fun shutdown()

  init {
    System.loadLibrary("ai-chat")
    init(nativeLibDir)
    initialized = true
  }

  suspend fun loadModel(path: String, systemPrompt: String, projectorPath: String? = null) = mutex.withLock {
    withContext(Dispatchers.IO) {
      val file = File(path)
      require(file.isFile && file.canRead()) { "GGUF model is unavailable." }
      if (loaded) {
        unloadProjector()
        unload()
        loaded = false
      }
      check(load(path) == 0) { "llama.cpp could not load this GGUF architecture." }
      check(prepare() == 0) { "llama.cpp could not allocate the model context." }
      loaded = true
      if (projectorPath != null) {
        val projector = File(projectorPath)
        require(projector.isFile && projector.canRead()) { "GGUF vision projector is unavailable." }
        check(loadProjector(projectorPath) == 0) {
          "llama.cpp could not load this multimodal projector."
        }
      }
      if (systemPrompt.isNotBlank()) {
        check(processSystemPrompt(systemPrompt) == 0) { "GGUF system prompt failed." }
      }
    }
  }

  suspend fun generate(
    prompt: String,
    predictLength: Int,
    imageBytes: ByteArray? = null,
    onToken: (String) -> Unit,
  ): String = mutex.withLock {
    withContext(Dispatchers.IO) {
      check(loaded) { "Load a GGUF model first." }
      require(prompt.isNotBlank()) { "Message is empty." }
      val processResult =
        if (imageBytes == null) {
          processUserPrompt(prompt, predictLength)
        } else {
          processImagePrompt(prompt, imageBytes, predictLength)
        }
      check(processResult == 0) { "GGUF prompt processing failed ($processResult)." }
      val output = StringBuilder()
      while (true) {
        currentCoroutineContext().ensureActive()
        val token = generateNextToken() ?: break
        if (token.isNotEmpty()) {
          output.append(token)
          onToken(token)
        }
      }
      output.toString()
    }
  }

  suspend fun reset(path: String, systemPrompt: String, projectorPath: String? = null) {
    mutex.withLock {
      withContext(Dispatchers.IO) {
        check(loaded) { "Load a GGUF model first." }
        check(processSystemPrompt(systemPrompt.ifBlank { " " }) == 0) {
          "GGUF conversation reset failed."
        }
      }
    }
  }

  override fun close() {
    if (!initialized) return
    runCatching {
      if (loaded) {
        unloadProjector()
        unload()
      }
      shutdown()
    }
    loaded = false
    initialized = false
  }
}
