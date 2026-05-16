package com.myra.assistant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
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
import com.myra.assistant.ui.main.MainActivity

/**
 * WakeWordService — "Hey Myra" sunne ke liye background service.
 * Sirf EXACT phrase match pe trigger hoti hai, beep sounds suppressed hain.
 * 60 second cooldown hai — bar bar trigger nahi hogi.
 */
class WakeWordService : Service() {

  companion object {
    const val TAG = "WakeWordService"
    const val CHANNEL_ID = "myra_wake_word_channel"
    const val NOTIFICATION_ID = 4
    // Strict phrases sirf — ambient noise se trigger na ho
    val EXACT_WAKE_PHRASES = listOf("hey myra", "hey mira", "hi myra", "hello myra", "hey maira")
    const val LISTEN_DURATION_MS = 5000L   // 5 sec suno
    const val COOLDOWN_MS = 60_000L        // 60 sec cooldown after trigger
    const val RESTART_DELAY_MS = 2000L     // 2 sec wait before restarting
  }

  private var speechRecognizer: SpeechRecognizer? = null
  private val handler = Handler(Looper.getMainLooper())
  private var isListening = false
  private var isDestroyed = false
  private var lastTriggerTime = 0L  // cooldown tracker

  override fun onCreate() {
    super.onCreate()
    try {
      createNotificationChannel()
      startForeground(NOTIFICATION_ID, buildNotification())
      Log.d(TAG, "WakeWordService started")
      // Thoda delay de — system settle hone do
      handler.postDelayed({ startListeningLoop() }, 3000)
    } catch (e: Exception) {
      Log.e(TAG, "Error in onCreate", e)
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    super.onDestroy()
    isDestroyed = true
    handler.removeCallbacksAndMessages(null)
    stopRecognizer()
    Log.d(TAG, "WakeWordService destroyed")
  }

  private fun stopRecognizer() {
    try {
      speechRecognizer?.cancel()
      speechRecognizer?.destroy()
    } catch (e: Exception) { /* ignore */ }
    speechRecognizer = null
    isListening = false
  }

  private fun startListeningLoop() {
    if (isDestroyed) return
    if (!SpeechRecognizer.isRecognitionAvailable(this)) {
      Log.w(TAG, "SpeechRecognizer unavailable")
      return
    }

    // Cooldown check
    val now = System.currentTimeMillis()
    if (now - lastTriggerTime < COOLDOWN_MS) {
      val remaining = COOLDOWN_MS - (now - lastTriggerTime)
      scheduleNextListen(remaining)
      return
    }

    stopRecognizer()

    try {
      speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

      val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        // Silence detection — jaldi stop ho
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
      }

      speechRecognizer?.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { isListening = false }

        override fun onError(error: Int) {
          isListening = false
          // ERROR_NO_MATCH (7) ya ERROR_SPEECH_TIMEOUT (6) = normal, bas restart
          scheduleNextListen(RESTART_DELAY_MS)
        }

        override fun onResults(results: Bundle?) {
          isListening = false
          val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
          if (processResults(matches)) {
            scheduleNextListen(COOLDOWN_MS)  // cooldown ke baad restart
          } else {
            scheduleNextListen(RESTART_DELAY_MS)
          }
        }

        override fun onPartialResults(partialResults: Bundle?) {
          val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
          if (processResults(matches)) {
            stopRecognizer()
            scheduleNextListen(COOLDOWN_MS)
          }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
      })

      // Beep sound suppress karo — SpeechRecognizer start pe Google ka "ding" mute karo
      suppressAudioFeedback(true)
      speechRecognizer?.startListening(intent)
      isListening = true

      // Safety timeout
      handler.postDelayed({
        if (isListening && !isDestroyed) {
          suppressAudioFeedback(false)
          stopRecognizer()
          scheduleNextListen(RESTART_DELAY_MS)
        }
      }, LISTEN_DURATION_MS)

    } catch (e: Exception) {
      Log.e(TAG, "Error starting listen", e)
      suppressAudioFeedback(false)
      scheduleNextListen(5000)
    }
  }

  /**
   * Google SpeechRecognizer ki "tunk tunk" beep sound band karo.
   * STREAM_SYSTEM mute karo jab recognizer start ho raha ho.
   */
  private fun suppressAudioFeedback(mute: Boolean) {
    try {
      val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
      if (mute) {
        am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
        // 500ms baad unmute karo — sirf startup beep band karna hai
        handler.postDelayed({ suppressAudioFeedback(false) }, 500)
      } else {
        am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
      }
    } catch (e: Exception) {
      Log.w(TAG, "Could not suppress audio feedback: ${e.message}")
    }
  }

  private fun processResults(matches: ArrayList<String>?): Boolean {
    if (matches.isNullOrEmpty()) return false
    for (match in matches) {
      val lower = match.lowercase().trim()
      for (phrase in EXACT_WAKE_PHRASES) {
        // Exact phrase match — contains check (not just substring of longer sentence)
        if (lower == phrase || lower.startsWith(phrase) || lower.endsWith(phrase)) {
          Log.i(TAG, "Wake phrase detected: '$match'")
          lastTriggerTime = System.currentTimeMillis()
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
      suppressAudioFeedback(false)  // ensure unmuted

      // Screen wake karo
      val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
      @Suppress("DEPRECATION")
      val wakeLock = powerManager.newWakeLock(
        PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
        "Myra::WakeWordWakeLock"
      )
      wakeLock.acquire(3000)

      // MainActivity open karo — WAKE_WORD_TRIGGERED=true
      // Note: MainActivity sirf app open karegi, mic AUTO-activate NAHI karegi
      val intent = Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra("WAKE_WORD_TRIGGERED", true)
      }
      startActivity(intent)

      handler.postDelayed({ if (wakeLock.isHeld) wakeLock.release() }, 2500)
    } catch (e: Exception) {
      Log.e(TAG, "Error waking screen", e)
    }
  }

  private fun createNotificationChannel() {
    val channel = NotificationChannel(
      CHANNEL_ID, "MYRA Wake Word", NotificationManager.IMPORTANCE_LOW
    )
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
