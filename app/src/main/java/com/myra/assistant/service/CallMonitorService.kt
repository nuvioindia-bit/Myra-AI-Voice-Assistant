package com.myra.assistant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.IBinder
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.myra.assistant.R
import com.myra.assistant.ui.main.MainActivity

class CallMonitorService : Service() {

  companion object {
    const val TAG = "CallMonitorService"
    const val CHANNEL_ID = "myra_call_channel"
    const val NOTIFICATION_ID = 2
  }

  private var telephonyManager: TelephonyManager? = null
  private var phoneStateListener: PhoneStateListener? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
    startForeground(NOTIFICATION_ID, buildNotification())

    telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    phoneStateListener = object : PhoneStateListener() {
      override fun onCallStateChanged(state: Int, phoneNumber: String?) {
        when (state) {
          TelephonyManager.CALL_STATE_RINGING -> {
            Log.d(TAG, "Incoming call from: $phoneNumber")
            val callerName = resolveCallerName(phoneNumber)
            val intent = Intent(this@CallMonitorService, MainActivity::class.java).apply {
              flags = Intent.FLAG_ACTIVITY_NEW_TASK
              putExtra("INCOMING_CALL", true)
              putExtra("CALLER_NAME", callerName ?: phoneNumber ?: "Unknown")
            }
            startActivity(intent)
          }
          TelephonyManager.CALL_STATE_IDLE -> {
            sendBroadcast(Intent("com.myra.CALL_ENDED"))
          }
          TelephonyManager.CALL_STATE_OFFHOOK -> {
            // Call in progress
          }
        }
      }
    }
    try {
      telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
    } catch (e: Exception) {
      Log.e(TAG, "Error starting phone state listener", e)
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    super.onDestroy()
    try {
      telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
    } catch (e: Exception) {
      Log.e(TAG, "Error stopping phone state listener", e)
    }
  }

  private fun createNotificationChannel() {
    val channel = NotificationChannel(
      CHANNEL_ID,
      "MYRA Call Monitor",
      NotificationManager.IMPORTANCE_LOW
    )
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(channel)
  }

  private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
    .setContentTitle("MYRA Call Monitor")
    .setContentText("Monitoring incoming calls")
    .setSmallIcon(R.drawable.bg_mic_button)
    .setOngoing(true)
    .build()

  private fun resolveCallerName(phoneNumber: String?): String? {
    if (phoneNumber.isNullOrBlank()) return null
    val normalized = phoneNumber.replace("[^0-9]".toRegex(), "")
    val lastDigits = normalized.takeLast(7)

    val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
    val projection = arrayOf(
      ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
      ContactsContract.CommonDataKinds.Phone.NUMBER
    )
    var cursor: Cursor? = null
    try {
      cursor = contentResolver.query(uri, projection, null, null, null)
      cursor?.use {
        while (it.moveToNext()) {
          val name = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
          val number = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
          val normNumber = number.replace("[^0-9]".toRegex(), "")
          if (normNumber.contains(lastDigits)) {
            return name
          }
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error resolving contact", e)
    }
    return null
  }
}
