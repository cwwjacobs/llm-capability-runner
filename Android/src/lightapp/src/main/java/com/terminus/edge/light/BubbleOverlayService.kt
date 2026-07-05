package com.terminus.edge.light

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import android.graphics.Bitmap
import android.graphics.PixelFormat.RGBA_8888
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BubbleOverlayService : Service() {
  private lateinit var windowManager: WindowManager
  private var floatingView: View? = null
  private var mediaProjection: MediaProjection? = null
  private val scope = CoroutineScope(Dispatchers.Main)
  
  companion object {
    var mediaProjectionIntent: Intent? = null
    var onImageCaptured: ((File) -> Unit)? = null
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
    val notification = NotificationCompat.Builder(this, "BUBBLE_CHANNEL")
      .setContentTitle("Runner Bubble Active")
      .setContentText("Tap to capture screen context")
      .setSmallIcon(android.R.drawable.ic_menu_camera)
      .build()
    startForeground(1, notification)
    
    windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
    
    // We'll just construct a simple floating view programmatically
    floatingView = FrameLayout(this).apply {
      val imageView = ImageView(this@BubbleOverlayService)
      imageView.setImageResource(android.R.drawable.ic_menu_camera)
      imageView.setBackgroundColor(android.graphics.Color.MAGENTA)
      imageView.setPadding(20, 20, 20, 20)
      addView(imageView, FrameLayout.LayoutParams(150, 150))
    }

    val layoutParams = WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
      PixelFormat.TRANSLUCENT
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      x = 0
      y = 100
    }

    floatingView?.setOnTouchListener(object : View.OnTouchListener {
      private var initialX = 0
      private var initialY = 0
      private var initialTouchX = 0f
      private var initialTouchY = 0f
      private var isClick = false

      override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.action) {
          MotionEvent.ACTION_DOWN -> {
            initialX = layoutParams.x
            initialY = layoutParams.y
            initialTouchX = event.rawX
            initialTouchY = event.rawY
            isClick = true
            return true
          }
          MotionEvent.ACTION_MOVE -> {
            val dx = event.rawX - initialTouchX
            val dy = event.rawY - initialTouchY
            if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isClick = false
            layoutParams.x = initialX + dx.toInt()
            layoutParams.y = initialY + dy.toInt()
            windowManager.updateViewLayout(floatingView, layoutParams)
            return true
          }
          MotionEvent.ACTION_UP -> {
            if (isClick) captureScreen()
            return true
          }
        }
        return false
      }
    })

    windowManager.addView(floatingView, layoutParams)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (mediaProjection == null && mediaProjectionIntent != null) {
      val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
      mediaProjection = projectionManager.getMediaProjection(android.app.Activity.RESULT_OK, mediaProjectionIntent!!)
    }
    return START_NOT_STICKY
  }

  private fun captureScreen() {
    val mp = mediaProjection ?: return
    val metrics = resources.displayMetrics
    val width = metrics.widthPixels
    val height = metrics.heightPixels
    val density = metrics.densityDpi

    val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    val virtualDisplay = mp.createVirtualDisplay(
      "ScreenCapture",
      width, height, density,
      android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
      imageReader.surface, null, null
    )

    imageReader.setOnImageAvailableListener({ reader ->
      val image = reader.acquireLatestImage()
      if (image != null) {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        
        val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
        
        image.close()
        virtualDisplay.release()
        reader.setOnImageAvailableListener(null, null)

        scope.launch {
          val file = File(cacheDir, "bubble_capture_${System.currentTimeMillis()}.png")
          withContext(Dispatchers.IO) {
            FileOutputStream(file).use { out ->
              croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
          }
          onImageCaptured?.invoke(file)
        }
      }
    }, null)
  }

  private fun createNotificationChannel() {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
      val channel = NotificationChannel("BUBBLE_CHANNEL", "Bubble Overlay", NotificationManager.IMPORTANCE_LOW)
      val manager = getSystemService(NotificationManager::class.java)
      manager.createNotificationChannel(channel)
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    floatingView?.let { windowManager.removeView(it) }
    mediaProjection?.stop()
  }
}
