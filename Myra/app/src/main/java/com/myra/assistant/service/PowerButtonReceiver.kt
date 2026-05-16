package com.myra.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class PowerButtonReceiver : BroadcastReceiver() {

  companion object {
    const val TAG = "PowerButtonReceiver"
    const val DOUBLE_PRESS_INTERVAL_MS = 600L
  }

  private var lastScreenOffTime: Long = 0
  private var pressCount = 0

  override fun onReceive(context: Context, intent: Intent) {
    when (intent.action) {
      Intent.ACTION_SCREEN_OFF -> {
        val now = System.currentTimeMillis()
        if (now - lastScreenOffTime < DOUBLE_PRESS_INTERVAL_MS) {
          pressCount++
          if (pressCount >= 2) {
            Log.d(TAG, "Double power press detected — showing overlay")
            pressCount = 0
            val serviceIntent = Intent(context, MyraOverlayService::class.java).apply {
              action = MyraOverlayService.ACTION_SHOW_OVERLAY
            }
            context.startForegroundService(serviceIntent)
          }
        } else {
          pressCount = 1
        }
        lastScreenOffTime = now
      }
      Intent.ACTION_SCREEN_ON -> {
        // Reset on screen on
      }
    }
  }
}
