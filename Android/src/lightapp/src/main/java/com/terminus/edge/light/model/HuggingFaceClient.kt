package com.terminus.edge.light.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object HuggingFaceClient {
  private const val API_BASE_URL = "https://huggingface.co/api"

  suspend fun listModels(query: String, token: String? = null): List<HfModelInfo> = withContext(Dispatchers.IO) {
    try {
      val url = URL("$API_BASE_URL/models?search=$query&limit=20")
      val connection = url.openConnection() as HttpURLConnection
      try {
        connection.requestMethod = "GET"
        if (!token.isNullOrBlank()) {
          connection.setRequestProperty("Authorization", "Bearer $token")
        }
        if (connection.responseCode == 200) {
          val response = connection.inputStream.bufferedReader().readText()
          val jsonArray = JSONArray(response)
          val result = mutableListOf<HfModelInfo>()
          for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            result.add(
              HfModelInfo(
                id = obj.getString("id"),
                downloads = obj.optInt("downloads", 0)
              )
            )
          }
          result
        } else emptyList()
      } finally {
        connection.disconnect()
      }
    } catch (e: Exception) {
      emptyList()
    }
  }

  suspend fun listModelFiles(repoId: String, token: String? = null): List<String> = withContext(Dispatchers.IO) {
    val url = URL("$API_BASE_URL/models/$repoId/tree/main")
    val connection = url.openConnection() as HttpURLConnection
    try {
      connection.requestMethod = "GET"
      if (!token.isNullOrBlank()) {
        connection.setRequestProperty("Authorization", "Bearer $token")
      }
      if (connection.responseCode == 200) {
        val response = connection.inputStream.bufferedReader().readText()
        val jsonArray = JSONArray(response)
        val result = mutableListOf<String>()
        for (i in 0 until jsonArray.length()) {
          val obj = jsonArray.getJSONObject(i)
          if (obj.getString("type") == "file") {
            val path = obj.getString("path")
            if (path.endsWith(".tflite") || path.endsWith(".litertlm") || path.endsWith(".bin")) {
              result.add(path)
            }
          }
        }
        result
      } else emptyList()
    } catch (e: Exception) {
      emptyList()
    } finally {
      connection.disconnect()
    }
  }

  suspend fun downloadFile(repoId: String, path: String, destFile: File, token: String? = null, onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
    val url = URL("https://huggingface.co/$repoId/resolve/main/$path")
    val connection = url.openConnection() as HttpURLConnection
    try {
      connection.requestMethod = "GET"
      if (!token.isNullOrBlank()) {
        connection.setRequestProperty("Authorization", "Bearer $token")
      }
      if (connection.responseCode == 200 || connection.responseCode == 302) {
        val contentLength = connection.contentLength.toLong()
        var downloaded = 0L
        connection.inputStream.use { input ->
          FileOutputStream(destFile).use { output ->
            val buffer = ByteArray(8 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
              output.write(buffer, 0, read)
              downloaded += read
              if (contentLength > 0) {
                onProgress(downloaded.toFloat() / contentLength)
              }
            }
            output.fd.sync()
          }
        }
        true
      } else false
    } catch (e: Exception) {
      false
    } finally {
      connection.disconnect()
    }
  }
}

data class HfModelInfo(val id: String, val downloads: Int)
