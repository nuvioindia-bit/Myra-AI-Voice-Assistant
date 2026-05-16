package com.myra.assistant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.os.Build
import android.os.IBinder
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.myra.assistant.R
import com.myra.assistant.ui.main.MainActivity
import java.util.concurrent.Executor

class CallMonitorService : Service() {

  companion object {
    const val TAG = "CallMonitorService"
    const val CHANNEL_ID = "myra_call_channel"
    const val NOTIFICATION_ID = 2
  }

  private var telephonyManager: TelephonyManager? = null

  // Android 12+ ke liye TelephonyCallback
  private var telephonyCallback: TelephonyCallback? = null

  // Android 11 aur usse neeche ke liye PhoneStateListener
  @Suppress("DEPRECATION")
  private var phoneStateListener: PhoneStateListener? = null

  override fun onCreate() {
    super.onCreate()
    try {
      createNotificationChannel()
      startForeground(NOTIFICATION_ID, buildNotification())
      telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
      registerPhoneListener()
    } catch (e: Exception) {
      Log.e(TAG, "Error in onCreate", e)
    }
  }

  private fun registerPhoneListener() {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Android 12+ (API 31+) — TelephonyCallback use karo
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
          override fun onCallStateChanged(state: Int) {
            handleCallState(state, null)
          }
        }
        telephonyCallback = callback
        val executor: Executor = mainExecutor
        telephonyManager?.registerTelephonyCallback(executor, callback)
        Log.d(TAG, "TelephonyCallback registered (Android 12+)")
      } else {
        // Android 11 aur neeche — PhoneStateListener
        @Suppress("DEPRECATION")
        val listener = object : PhoneStateListener() {
          @Deprecated("Deprecated in Java")
          override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            handleCallState(state, phoneNumber)
          }
        }
        phoneStateListener = listener
        @Suppress("DEPRECATION")
        telephonyManager?.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        Log.d(TAG, "PhoneStateListener registered (Android < 12)")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error registering phone listener", e)
    }
  }

  private fun handleCallState(state: Int, phoneNumber: String?) {
    try {
      when (state) {
        TelephonyManager.CALL_STATE_RINGING -> {
          Log.d(TAG, "Incoming call: $phoneNumber")
          val callerName = try { resolveCallerName(phoneNumber) } catch (e: Exception) { null }
          val intent = Intent(this@CallMonitorService, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra("INCOMING_CALL", true)
            putExtra("CALLER_NAME", callerName ?: phoneNumber ?: "Unknown")
          }
          try { startActivity(intent) } catch (e: Exception) { Log.e(TAG, "Cannot start activity", e) }
        }
        TelephonyManager.CALL_STATE_IDLE -> {
          try { sendBroadcast(Intent("com.myra.CALL_ENDED")) } catch (e: Exception) { Log.e(TAG, "Broadcast error", e) }
        }
        TelephonyManager.CALL_STATE_OFFHOOK -> { /* Call in progress */ }
      }
    } catch (e: Exception) {
      Log.e(TAG, "handleCallState error", e)
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    super.onDestroy()
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        telephonyCallback?.let { telephonyManager?.unregisterTelephonyCallback(it) }
      } else {
        @Suppress("DEPRECATION")
        phoneStateListener?.let { telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE) }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error in onDestroy", e)
    }
  }

  private fun createNotificationChannel() {
    val channel = NotificationChannel(
      CHANNEL_ID,
      "MYRA Call Monitor",
      NotificationManager.IMPORTANCE_LOW
    )
    (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
      .createNotificationChannel(channel)
  }

  private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
    .setContentTitle("MYRA")
    .setContentText("Call monitoring active")
    .setSmallIcon(android.R.drawable.ic_menu_call)
    .setOngoing(true)
    .build()

  private fun resolveCallerName(phoneNumber: String?): String? {
    if (phoneNumber.isNullOrBlank()) return null
    val lastDigits = phoneNumber.replace("[^0-9]".toRegex(), "").takeLast(7)
    val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
    val projection = arrayOf(
      ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
      ContactsContract.CommonDataKinds.Phone.NUMBER
    )
    var cursor: Cursor? = null
    return try {
      cursor = contentResolver.query(uri, projection, null, null, null)
      cursor?.use {
        while (it.moveToNext()) {
          val name = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
          val number = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
          if (number.replace("[^0-9]".toRegex(), "").contains(lastDigits)) return name
        }
      }
      null
    } catch (e: Exception) {
      Log.e(TAG, "Error resolving contact", e)
      null
    } finally {
      cursor?.close()
    }
  }
}
