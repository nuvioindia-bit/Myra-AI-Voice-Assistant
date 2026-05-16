package com.myra.assistant.ui.main

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.View
import android.view.animation.AlphaAnimation
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.myra.assistant.BuildConfig
import com.myra.assistant.R
import com.myra.assistant.ai.AudioEngine
import com.myra.assistant.ai.CommandParser
import com.myra.assistant.ai.GeminiLiveClient
import com.myra.assistant.model.ChatMessage
import com.myra.assistant.service.CallMonitorService
import com.myra.assistant.ui.settings.SettingsActivity
import com.myra.assistant.viewmodel.MainViewModel

class MainActivity : AppCompatActivity() {

  companion object {
    const val TAG = "MainActivity"
    const val PERMISSIONS_REQUEST_CODE = 100
    const val CALL_STT_TIMEOUT_MS = 4500L
  }

  private val viewModel: MainViewModel by viewModels()
  private lateinit var prefs: android.content.SharedPreferences
  private lateinit var geminiLive: GeminiLiveClient
  private lateinit var audioEngine: AudioEngine
  private lateinit var chatAdapter: ChatAdapter

  private lateinit var orbView: OrbAnimationView
  private lateinit var waveformView: WaveformView
  private lateinit var statusText: TextView
  private lateinit var micButton: ImageButton
  private lateinit var settingsBtn: ImageButton
  private lateinit var chatRecycler: RecyclerView
  private lateinit var redOverlay: View
  private lateinit var batteryText: TextView
  private lateinit var ramText: TextView
  private lateinit var timeText: TextView

  private val inputBuffer = StringBuilder()
  private val outputBuffer = StringBuilder()
  private var isInCallMode = false
  private var isActive = false
  private var isMuted = false

  private var callStt: SpeechRecognizer? = null
  private val handler = Handler(Looper.getMainLooper())
  private var receiverRegistered = false

  private val callEndedReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      try {
        if (intent?.action == "com.myra.CALL_ENDED") {
          isInCallMode = false
          setActiveMode(false)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error in call ended receiver", e)
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    try {
      setContentView(R.layout.activity_main)
    } catch (e: Exception) {
      Log.e(TAG, "Error setting content view", e)
      Toast.makeText(this, "Error loading UI", Toast.LENGTH_LONG).show()
      finish()
      return
    }

    prefs = getSharedPreferences("myra_prefs", Context.MODE_PRIVATE)

    try {
      initViews()
    } catch (e: Exception) {
      Log.e(TAG, "Error initializing views", e)
      Toast.makeText(this, "UI initialization failed", Toast.LENGTH_LONG).show()
      finish()
      return
    }

    try {
      setupChat()
    } catch (e: Exception) {
      Log.e(TAG, "Error setting up chat", e)
    }

    try {
      setupObservers()
    } catch (e: Exception) {
      Log.e(TAG, "Error setting up observers", e)
    }

    try {
      setupClickListeners()
    } catch (e: Exception) {
      Log.e(TAG, "Error setting up click listeners", e)
    }

    // Permissions check karo — grant hone ke baad services start hongi
    try {
      checkAndRequestPermissions()
    } catch (e: Exception) {
      Log.e(TAG, "Error checking permissions", e)
      // Permission check fail hone par bhi app chalti rahe
      postPermissionsSetup()
    }

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        registerReceiver(callEndedReceiver, IntentFilter("com.myra.CALL_ENDED"), ContextCompat.RECEIVER_EXPORTED)
      } else {
        registerReceiver(callEndedReceiver, IntentFilter("com.myra.CALL_ENDED"))
      }
      receiverRegistered = true
    } catch (e: Exception) {
      Log.e(TAG, "Error registering receiver", e)
    }

    try {
      if (intent.getBooleanExtra("INCOMING_CALL", false)) {
        val callerName = intent.getStringExtra("CALLER_NAME") ?: "Unknown"
        announceCall(callerName)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error handling incoming call intent", e)
    }

    // "Hey Myra" wake word se trigger hua? Auto-activate listening
    try {
      if (intent.getBooleanExtra("WAKE_WORD_TRIGGERED", false)) {
        Log.d(TAG, "Wake word triggered — auto activating")
        handler.postDelayed({
          try {
            if (::audioEngine.isInitialized) audioEngine.start(this@MainActivity)
            if (::geminiLive.isInitialized && !geminiLive.isConnected) geminiLive.connect()
            setActiveMode(true)
            statusText.text = "Hey! I'm listening..."
          } catch (e: Exception) { Log.e(TAG, "Wake word activation error", e) }
        }, 300)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error handling wake word intent", e)
    }

    try {
      updateSystemInfo()
    } catch (e: Exception) {
      Log.e(TAG, "Error updating system info", e)
    }
  }

  private fun initViews() {
    orbView = findViewById(R.id.orbView)
    waveformView = findViewById(R.id.waveformView)
    statusText = findViewById(R.id.statusText)
    statusText.text = getString(R.string.tap_to_speak)
    micButton = findViewById(R.id.micButton)
    settingsBtn = findViewById(R.id.settingsBtn)
    chatRecycler = findViewById(R.id.chatRecycler)
    redOverlay = findViewById(R.id.redOverlay)
    batteryText = findViewById(R.id.batteryText)
    ramText = findViewById(R.id.ramText)
    timeText = findViewById(R.id.timeText)
  }

  private fun checkAndRequestPermissions() {
    val permissions = mutableListOf(
      Manifest.permission.RECORD_AUDIO,
      Manifest.permission.CALL_PHONE,
      Manifest.permission.READ_CONTACTS,
      Manifest.permission.READ_PHONE_STATE,
      Manifest.permission.SEND_SMS,
      Manifest.permission.RECEIVE_SMS
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      permissions.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    val needed = permissions.filter {
      ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }
    if (needed.isNotEmpty()) {
      // Permission dialog dikhao — result milne par postPermissionsSetup() call hogi
      ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSIONS_REQUEST_CODE)
    } else {
      // Sab permissions pehle se grant hain — direct setup karo
      postPermissionsSetup()
    }
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == PERMISSIONS_REQUEST_CODE) {
      // Permission ka result aaya — ab setup karo (chahe deny ho ya grant)
      postPermissionsSetup()
    }
  }

  // Permissions ke baad safe setup — services init karo, audio mic click pe start hogi
  private fun postPermissionsSetup() {
    try {
      setupAudioEngine()
    } catch (e: Exception) {
      Log.e(TAG, "Error setting up audio engine", e)
    }
    try {
      setupGeminiLive()
      // Auto-connect try karo agar key available hai
      val apiKey = BuildConfig.GEMINI_API_KEY
      val manualKey = prefs.getString("api_key", "") ?: ""
      if ((apiKey.isNotBlank() || manualKey.isNotBlank()) && ::geminiLive.isInitialized) {
        geminiLive.connect()
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error setting up Gemini", e)
    }
    try {
      startService(Intent(this, CallMonitorService::class.java))
    } catch (e: Exception) {
      Log.e(TAG, "Error starting CallMonitorService", e)
    }
    // WakeWordService bhi start karo — "Hey Myra" lock screen detection
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(Intent(this, com.myra.assistant.service.WakeWordService::class.java))
      } else {
        startService(Intent(this, com.myra.assistant.service.WakeWordService::class.java))
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error starting WakeWordService", e)
    }
  }

  private fun setupAudioEngine() {
    try {
      audioEngine = AudioEngine()
      audioEngine.onAudioChunk = { chunk ->
        try {
          if (isActive && !isInCallMode) {
            geminiLive.sendAudioChunk(chunk)
          }
        } catch (e: Exception) {
          Log.e(TAG, "Error sending audio chunk", e)
        }
      }
      audioEngine.onAmplitudeChanged = { rms ->
        runOnUiThread {
          try {
            waveformView.setAmplitude(rms)
            if (rms > 0.1f) {
              orbView.setState(OrbAnimationView.State.LISTENING)
            }
          } catch (e: Exception) {
            Log.e(TAG, "Error updating amplitude", e)
          }
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error creating audio engine", e)
    }
  }

  private fun setupGeminiLive() {
    try {
      geminiLive = GeminiLiveClient(this, prefs)
      geminiLive.onSetupComplete = {
        runOnUiThread {
          try {
            Log.d(TAG, "Gemini setup complete — ready for audio")
            // Agar user pehle se listening mode mein hai, status update karo
            if (isActive) {
              statusText.text = "Listening..."
            } else {
              statusText.text = getString(R.string.connected_tap_to_speak)
            }
          } catch (e: Exception) {
            Log.e(TAG, "Error on setup complete", e)
          }
        }
      }

      geminiLive.onError = { error ->
        runOnUiThread {
          try {
            Log.e(TAG, "Gemini error: $error")
            Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
            statusText.text = "Error: $error"
            isActive = false
            orbView.setState(OrbAnimationView.State.IDLE)
          } catch (e: Exception) {
            Log.e(TAG, "Error displaying error", e)
          }
        }
      }
      geminiLive.onInputTranscript = { text ->
        try {
          inputBuffer.append(text)
        } catch (e: Exception) {
          Log.e(TAG, "Error on input transcript", e)
        }
      }
      geminiLive.onOutputTranscript = { text ->
        try {
          outputBuffer.append(text)
          runOnUiThread {
            orbView.setState(OrbAnimationView.State.SPEAKING)
          }
        } catch (e: Exception) {
          Log.e(TAG, "Error on output transcript", e)
        }
      }
      geminiLive.onAudioData = { data ->
        try {
          audioEngine.queueAudio(data)
          audioEngine.setSuppressed(true)
        } catch (e: Exception) {
          Log.e(TAG, "Error on audio data", e)
        }
      }
      geminiLive.onTurnComplete = {
        try {
          audioEngine.setSuppressed(false)
          runOnUiThread {
            flushTranscripts()
            if (!isActive) orbView.setState(OrbAnimationView.State.IDLE)
          }
        } catch (e: Exception) {
          Log.e(TAG, "Error on turn complete", e)
        }
      }
      // onError pehle se define hai (upar)
      val apiKey = BuildConfig.GEMINI_API_KEY
      val manualKey = prefs.getString("api_key", "") ?: ""
      if (apiKey.isBlank() && manualKey.isBlank()) {
        statusText.text = getString(R.string.api_key_required)
      } else {
        geminiLive.connect()
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error setting up Gemini Live", e)
    }
  }

  private fun setupChat() {
    try {
      chatAdapter = ChatAdapter()
      chatRecycler.layoutManager = LinearLayoutManager(this).apply {
        stackFromEnd = true
      }
      chatRecycler.adapter = chatAdapter
    } catch (e: Exception) {
      Log.e(TAG, "Error setting up chat", e)
    }
  }

  private fun setupObservers() {
    try {
      viewModel.commandResult.observe(this) { result ->
        try {
          result?.let {
            chatAdapter.addMessage(ChatMessage(it, false))
            geminiLive.sendText(it)
          }
        } catch (e: Exception) {
          Log.e(TAG, "Error in command result observer", e)
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error setting up observers", e)
    }
  }

  private fun setupClickListeners() {
    micButton.setOnClickListener {
      try {
        if (isMuted) {
          isMuted = false
          audioEngine.setMuted(false)
          micButton.setImageResource(android.R.drawable.ic_btn_speak_now)
          statusText.text = getString(R.string.tap_to_speak)
          return@setOnClickListener
        }

        // Sabse pehle toggle karo — taake audio chunks capture hone lage
        isActive = !isActive

        if (isActive) {
          // STEP 1: Audio engine start karo (isActive already true hai)
          if (::audioEngine.isInitialized) {
            audioEngine.start(this@MainActivity)
          }

          // STEP 2: Gemini connected? Agar nahi to connect karo
          if (::geminiLive.isInitialized) {
            if (!geminiLive.isConnected) {
              val apiKey = BuildConfig.GEMINI_API_KEY
              val manualKey = prefs.getString("api_key", "") ?: ""
              if (apiKey.isBlank() && manualKey.isBlank()) {
                statusText.text = "API Key required — Go to Settings"
                Toast.makeText(this, "Add Gemini API Key in Settings", Toast.LENGTH_LONG).show()
                isActive = false
                return@setOnClickListener
              }
              statusText.text = "Connecting to MYRA..."
              geminiLive.connect()
            } else if (!geminiLive.isSetupComplete) {
              statusText.text = "MYRA connected — waiting for setup..."
            } else {
              statusText.text = "Listening..."
            }
          }

          orbView.setState(OrbAnimationView.State.ACTIVE)
          showRedOverlay(true)
        } else {
          // Band karo
          orbView.setState(OrbAnimationView.State.IDLE)
          statusText.text = getString(R.string.tap_to_speak)
          showRedOverlay(false)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error on mic click", e)
      }
    }

    micButton.setOnLongClickListener {
      try {
        audioEngine.clearPlaybackQueue()
        geminiLive.interrupt()
        orbView.setState(OrbAnimationView.State.IDLE)
        Toast.makeText(this, "Interrupted", Toast.LENGTH_SHORT).show()
      } catch (e: Exception) {
        Log.e(TAG, "Error on mic long click", e)
      }
      true
    }

    settingsBtn.setOnClickListener {
      try {
        startActivity(Intent(this, SettingsActivity::class.java))
      } catch (e: Exception) {
        Log.e(TAG, "Error opening settings", e)
        Toast.makeText(this, "Cannot open settings", Toast.LENGTH_SHORT).show()
      }
    }
  }

  private fun flushTranscripts() {
    try {
      val inputText = inputBuffer.toString().trim()
      val outputText = outputBuffer.toString().trim()
      inputBuffer.clear()
      outputBuffer.clear()

      if (inputText.isNotBlank()) {
        chatAdapter.addMessage(ChatMessage(inputText, true))
        val command = CommandParser.parse(inputText)
        command?.let { viewModel.executeCommand(it) }
      }
      if (outputText.isNotBlank()) {
        chatAdapter.addMessage(ChatMessage(outputText, false))
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error flushing transcripts", e)
    }
  }

  private fun showRedOverlay(show: Boolean) {
    try {
      val anim = AlphaAnimation(
        if (show) 0f else 0.08f,
        if (show) 0.08f else 0f
      )
      anim.duration = if (show) 300 else 500
      anim.fillAfter = true
      redOverlay.startAnimation(anim)
    } catch (e: Exception) {
      Log.e(TAG, "Error showing red overlay", e)
    }
  }

  fun announceCall(callerName: String) {
    try {
      isInCallMode = true
      setActiveMode(true)
      val prompt = "Sir, $callerName ka call aa raha hai. Uthau ya reject karu?"
      geminiLive.sendText(prompt)

      handler.postDelayed({
        startCallSTT()
      }, CALL_STT_TIMEOUT_MS)
    } catch (e: Exception) {
      Log.e(TAG, "Error announcing call", e)
    }
  }

  private fun startCallSTT() {
    try {
      if (!isInCallMode) return
      if (!SpeechRecognizer.isRecognitionAvailable(this)) {
        Log.w(TAG, "Speech recognition not available")
        return
      }
      callStt = SpeechRecognizer.createSpeechRecognizer(this)
      val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
      }
      callStt?.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) {
          try {
            startCallSTT()
          } catch (e: Exception) {
            Log.e(TAG, "Error restarting STT", e)
          }
        }
        override fun onResults(results: Bundle?) {
          try {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.lowercase() ?: ""
            when {
              text.contains("uthao") || text.contains("haan") || text.contains("accept") -> {
                viewModel.acceptCall()
                geminiLive.sendText("Call utha liya hai Sir.")
              }
              text.contains("reject") || text.contains("nahi") || text.contains("mat") -> {
                viewModel.rejectCall()
                geminiLive.sendText("Call reject kar diya Sir.")
              }
              else -> startCallSTT()
            }
            isInCallMode = false
            setActiveMode(false)
          } catch (e: Exception) {
            Log.e(TAG, "Error in STT results", e)
          }
        }
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
      })
      callStt?.startListening(intent)
    } catch (e: Exception) {
      Log.e(TAG, "Error starting call STT", e)
    }
  }

  private fun setActiveMode(active: Boolean) {
    try {
      isActive = active
      showRedOverlay(active)
      if (active) {
        orbView.setState(OrbAnimationView.State.ACTIVE)
      } else {
        orbView.setState(OrbAnimationView.State.IDLE)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error setting active mode", e)
    }
  }

  private fun updateSystemInfo() {
    try {
      val batteryStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
      } else {
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
      }
      val level = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: 0
      val scale = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: 100
      val pct = if (scale > 0) (level * 100 / scale) else 0
      batteryText.text = "BATT: $pct%"
    } catch (e: Exception) {
      Log.e(TAG, "Error reading battery", e)
      batteryText.text = "BATT: --%"
    }

    try {
      val memInfo = android.app.ActivityManager.MemoryInfo()
      val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
      am?.getMemoryInfo(memInfo)
      val availMB = memInfo.availMem / (1024 * 1024)
      ramText.text = "RAM: ${availMB}MB"
    } catch (e: Exception) {
      Log.e(TAG, "Error reading memory", e)
      ramText.text = "RAM: --"
    }

    try {
      timeText.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date())
    } catch (e: Exception) {
      Log.e(TAG, "Error updating time", e)
      timeText.text = "--:--"
    }

    handler.removeCallbacksAndMessages(null)
    handler.postDelayed({ updateSystemInfo() }, 30000)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    try {
      if (intent.getBooleanExtra("INCOMING_CALL", false)) {
        val callerName = intent.getStringExtra("CALLER_NAME") ?: "Unknown"
        announceCall(callerName)
      }
      if (intent.getBooleanExtra("WAKE_WORD_TRIGGERED", false)) {
        Log.d(TAG, "Wake word triggered in onNewIntent")
        handler.postDelayed({
          try {
            if (::audioEngine.isInitialized) audioEngine.start(this@MainActivity)
            if (::geminiLive.isInitialized && !geminiLive.isConnected) geminiLive.connect()
            setActiveMode(true)
            statusText.text = "Hey! I'm listening..."
          } catch (e: Exception) { Log.e(TAG, "Wake word activation error", e) }
        }, 300)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error in onNewIntent", e)
    }
  }

  override fun onResume() {
    super.onResume()
    try {
      val apiKey = BuildConfig.GEMINI_API_KEY
      val manualKey = prefs.getString("api_key", "") ?: ""
      if ((apiKey.isNotBlank() || manualKey.isNotBlank()) && ::geminiLive.isInitialized && !geminiLive.isConnected) {
        geminiLive.connect()
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error in onResume", e)
    }
  }

  override fun onPause() {
    super.onPause()
    try {
      handler.removeCallbacksAndMessages(null)
    } catch (e: Exception) {
      Log.e(TAG, "Error in onPause", e)
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    try {
      if (::geminiLive.isInitialized) geminiLive.disconnect()
    } catch (e: Exception) {
      Log.e(TAG, "Error disconnecting Gemini", e)
    }
    try {
      if (::audioEngine.isInitialized) audioEngine.stop()
    } catch (e: Exception) {
      Log.e(TAG, "Error stopping audio engine", e)
    }
    try {
      callStt?.destroy()
    } catch (e: Exception) {
      Log.e(TAG, "Error destroying STT", e)
    }
    try {
      if (receiverRegistered) {
        unregisterReceiver(callEndedReceiver)
        receiverRegistered = false
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error unregistering receiver", e)
    }
    try {
      handler.removeCallbacksAndMessages(null)
    } catch (e: Exception) {
      Log.e(TAG, "Error removing callbacks", e)
    }
  }
}
