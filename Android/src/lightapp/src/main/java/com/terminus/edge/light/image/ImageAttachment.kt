package com.terminus.edge.light.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import com.terminus.edge.light.trace.TraceIntegrity
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.max

data class ImageAttachment(
  val id: String = UUID.randomUUID().toString(),
  val displayName: String,
  val bitmap: Bitmap,
  val pngBytes: ByteArray,
  val width: Int,
  val height: Int,
  val sha256: String,
)

object ImageAttachmentLoader {
  fun load(context: Context, uri: Uri): ImageAttachment {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val bitmap =
      ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val largestDimension = max(info.size.width, info.size.height)
        if (largestDimension > MAX_DIMENSION) {
          decoder.setTargetSampleSize(
            (largestDimension + MAX_DIMENSION - 1) / MAX_DIMENSION
          )
        }
      }
    require(bitmap.width > 0 && bitmap.height > 0) { "The selected image is empty." }
    val bytes =
      ByteArrayOutputStream().use { output ->
        require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
          "The selected image could not be encoded."
        }
        output.toByteArray()
      }
    require(bytes.size <= MAX_ENCODED_BYTES) {
      "The selected image is too large after processing."
    }
    return ImageAttachment(
      displayName = queryDisplayName(context, uri) ?: "Attached image",
      bitmap = bitmap,
      pngBytes = bytes,
      width = bitmap.width,
      height = bitmap.height,
      sha256 = TraceIntegrity.sha256(bytes),
    )
  }

  private fun queryDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver
      .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
      ?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        cursor.getString(0)?.take(160)
      }

  private const val MAX_DIMENSION = 1024
  private const val MAX_ENCODED_BYTES = 8 * 1024 * 1024
}
