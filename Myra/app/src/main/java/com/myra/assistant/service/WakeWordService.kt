package com.myra.assistant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.myra.assistant.R
import com.myra.assistant.ui.main.MainActivity

/**
 * WakeWordService — "Hey Myra" detect karta hai lock screen par bhi.
 * SpeechRecognizer se periodic listening — "myra", "hey myra", "hello myra" detect hone par
 * MainActivity open karta hai with screen wake flags.
 */
class WakeWordService : Service() {

  companion object {
    const val TAG = "WakeWordService"
    const val CHANNEL_ID = "myra_wake_word_channel"
    const val NOTIFICATION_ID = 4
    const val WAKE_WORDS = "myra,hey myra,hello myra,hi myra,maira"
    const val LISTEN_INTERVAL_MS = 4000L  // har 4 sec mein 3 sec suno
    const val LISTEN_DURATION_MS = 3000L
  }

  private var speechRecognizer: SpeechRecognizer? = null
  private val handler = Handler(Looper.getMainLooper())
  private var isListening = false
  private var isDestroyed = false

  override fun onCreate() {
    super.onCreate()
    try {
      createNotificationChannel()
      startForeground(NOTIFICATION_ID, buildNotification())
      Log.d(TAG, "WakeWordService started")
      startListeningLoop()
    } catch (e: Exception) {
      Log.e(TAG, "Error in onCreate", e)
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    super.onDestroy()
    isDestroyed = true
    handler.removeCallbacksAndMessages(null)
    try {
      speechRecognizer?.destroy()
    } catch (e: Exception) {
      Log.e(TAG, "Error destroying SpeechRecognizer", e)
    }
    speechRecognizer = null
    Log.d(TAG, "WakeWordService destroyed")
  }

  private fun startListeningLoop() {
    if (isDestroyed) return
    if (!SpeechRecognizer.isRecognitionAvailable(this)) {
      Log.w(TAG, "SpeechRecognizer not available on this device")
      return
    }

    // Pehle se chal raha hai? Stop karke restart karo
    if (isListening) {
      try { speechRecognizer?.stopListening() } catch (e: Exception) { /* ignore */ }
      isListening = false
    }

    try {
      speechRecognizer?.destroy()
      speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

      val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
      }

      speechRecognizer?.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
          Log.d(TAG, "Ready for speech")
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
          isListening = false
        }
        override fun onError(error: Int) {
          Log.w(TAG, "SpeechRecognizer error: $error")
          isListening = false
          // Error ke baad restart karo
          scheduleNextListen(500)
        }
        override fun onResults(results: Bundle?) {
          isListening = false
          val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
          Log.d(TAG, "Results: $matches")
          processResults(matches)
          scheduleNextListen(500)
        }
        override fun onPartialResults(partialResults: Bundle?) {
          val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
          if (processResults(matches)) {
            // Wake word mil gaya — full results ka wait mat karo
            try { speechRecognizer?.stopListening() } catch (e: Exception) { }
          }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
      })

      speechRecognizer?.startListening(intent)
      isListening = true

      // Safety timeout — agar kuch nahi mila to restart karo
      handler.postDelayed({
        if (isListening && !isDestroyed) {
          try { speechRecognizer?.stopListening() } catch (e: Exception) { }
          isListening = false
          scheduleNextListen(500)
        }
      }, LISTEN_DURATION_MS)

    } catch (e: Exception) {
      Log.e(TAG, "Error starting listen", e)
      scheduleNextListen(2000)
    }
  }

  private fun processResults(matches: ArrayList<String>?): Boolean {
    if (matches.isNullOrEmpty()) return false
    val wakeWords = WAKE_WORDS.split(",")
    for (match in matches) {
      val lower = match.lowercase().replace(" ", "")
      for (word in wakeWords) {
        if (lower.contains(word.replace(" ", ""))) {
          Log.i(TAG, "WAKE WORD DETECTED: '$match'")
          wakeScreenAndLaunch()
          return true
        }
      }
    }
    return false
  }

  private fun scheduleNextListen(delayMs: Long) {
    if (isDestroyed) return
    handler.removeCallbacksAndMessages(null)
    handler.postDelayed({ startListeningLoop() }, delayMs)
  }

  private fun wakeScreenAndLaunch() {
    try {
      // Screen wake karo
      val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
      @Suppress("DEPRECATION")
      val wakeLock = powerManager.newWakeLock(
        PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
        "Myra::WakeWordWakeLock"
      )
      wakeLock.acquire(3000)

      // MainActivity open karo — lock screen par bhi
      val intent = Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        putExtra("WAKE_WORD_TRIGGERED", true)
      }
      startActivity(intent)

      // Wake lock release after delay
      handler.postDelayed({ if (wakeLock.isHeld) wakeLock.release() }, 2500)
    } catch (e: Exception) {
      Log.e(TAG, "Error waking screen", e)
    }
  }

  private fun createNotificationChannel() {
    val channel = NotificationChannel(
      CHANNEL_ID,
      "MYRA Wake Word",
      NotificationManager.IMPORTANCE_LOW
    ).apply {
      description = "Listening for 'Hey Myra' in background"
    }
    (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
      .createNotificationChannel(channel)
  }

  private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
    .setContentTitle("MYRA")
    .setContentText("Say 'Hey Myra' anytime")
    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
    .setOngoing(true)
    .build()
}
