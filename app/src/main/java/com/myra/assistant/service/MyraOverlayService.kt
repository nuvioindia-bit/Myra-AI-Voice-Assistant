package com.myra.assistant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import com.myra.assistant.R
import com.myra.assistant.ui.main.MainActivity
import com.myra.assistant.ui.main.OrbAnimationView

class MyraOverlayService : Service() {

  companion object {
    const val CHANNEL_ID = "myra_overlay_channel"
    const val NOTIFICATION_ID = 3
    var isRunning = false
    const val ACTION_SHOW_OVERLAY = "com.myra.SHOW_OVERLAY"
    const val ACTION_HIDE_OVERLAY = "com.myra.HIDE_OVERLAY"
  }

  private var windowManager: WindowManager? = null
  private var overlayView: View? = null
  private var params: WindowManager.LayoutParams? = null
  private var initialX = 0
  private var initialY = 0
  private var touchX = 0f
  private var touchY = 0f

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
    startForeground(NOTIFICATION_ID, buildNotification())
    windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_SHOW_OVERLAY -> showOverlay()
      ACTION_HIDE_OVERLAY -> hideOverlay()
      else -> showOverlay()
    }
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun showOverlay() {
    if (overlayView != null) return
    isRunning = true

    val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
    overlayView = inflater.inflate(R.layout.overlay_orb, null)

    val orbView = overlayView?.findViewById<OrbAnimationView>(R.id.overlayOrbView)
    orbView?.setState(OrbAnimationView.State.IDLE)

    val closeBtn = overlayView?.findViewById<ImageButton>(R.id.overlayCloseBtn)
    closeBtn?.setOnClickListener { hideOverlay() }

    params = WindowManager.LayoutParams(
      dpToPx(160),
      dpToPx(160),
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
      PixelFormat.TRANSLUCENT
    ).apply {
      gravity = Gravity.CENTER
      x = 0
      y = 0
    }

    overlayView?.setOnTouchListener { _, event ->
      when (event.action) {
        MotionEvent.ACTION_DOWN -> {
          initialX = params?.x ?: 0
          initialY = params?.y ?: 0
          touchX = event.rawX
          touchY = event.rawY
          false
        }
        MotionEvent.ACTION_MOVE -> {
          params?.x = initialX + (event.rawX - touchX).toInt()
          params?.y = initialY + (event.rawY - touchY).toInt()
          if (overlayView != null && params != null) {
            windowManager?.updateViewLayout(overlayView, params)
          }
          true
        }
        MotionEvent.ACTION_UP -> {
          val tap = kotlin.math.abs(event.rawX - touchX) < 10 && kotlin.math.abs(event.rawY - touchY) < 10
          if (tap) {
            val intent = Intent(this, MainActivity::class.java).apply {
              flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
          }
          true
        }
        else -> false
      }
    }

    windowManager?.addView(overlayView, params)
  }

  private fun hideOverlay() {
    try {
      overlayView?.let {
        windowManager?.removeView(it)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error removing overlay", e)
    }
    overlayView = null
    isRunning = false
  }

  override fun onDestroy() {
    super.onDestroy()
    try {
      hideOverlay()
    } catch (e: Exception) {
      Log.e(TAG, "Error destroying overlay service", e)
    }
  }

  private fun createNotificationChannel() {
    val channel = NotificationChannel(
      CHANNEL_ID,
      "MYRA Overlay",
      NotificationManager.IMPORTANCE_LOW
    )
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(channel)
  }

  private fun buildNotification(): android.app.Notification {
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("MYRA Overlay")
      .setContentText("Floating orb is active")
      .setSmallIcon(R.drawable.bg_mic_button)
      .setOngoing(true)
      .build()
  }

  private fun dpToPx(dp: Int): Int {
    return (dp * resources.displayMetrics.density).toInt()
  }
}
