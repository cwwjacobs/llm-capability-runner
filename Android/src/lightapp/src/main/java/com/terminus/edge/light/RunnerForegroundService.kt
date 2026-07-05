package com.terminus.edge.light

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class RunnerForegroundService : Service() {
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == "STOP") {
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }
    
    createNotificationChannel()
    val notification = NotificationCompat.Builder(this, "RUNNER_CHANNEL")
      .setContentTitle("Edge Lite Runner")
      .setContentText("Generating response...")
      .setSmallIcon(android.R.drawable.ic_popup_sync)
      .build()
    startForeground(2, notification)
    return START_NOT_STICKY
  }

  private fun createNotificationChannel() {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
      val channel = NotificationChannel("RUNNER_CHANNEL", "Runner Status", NotificationManager.IMPORTANCE_LOW)
      val manager = getSystemService(NotificationManager::class.java)
      manager.createNotificationChannel(channel)
    }
  }
}
