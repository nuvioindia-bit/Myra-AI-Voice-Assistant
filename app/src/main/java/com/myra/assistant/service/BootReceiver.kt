package com.myra.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

  companion object {
    const val TAG = "BootReceiver"
  }

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
      Log.d(TAG, "Boot completed — starting CallMonitorService")
      val serviceIntent = Intent(context, CallMonitorService::class.java)
      context.startForegroundService(serviceIntent)
    }
  }
}
