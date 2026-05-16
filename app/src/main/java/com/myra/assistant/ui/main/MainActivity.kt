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
import android.speech.tts.TextToSpeech
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

  private val callEndedReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == "com.myra.CALL_ENDED") {
        isInCallMode = false
        setActiveMode(false)
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    prefs = getSharedPreferences("myra_prefs", Context.MODE_PRIVATE)
    initViews()
    checkPermissions()
    setupAudioEngine()
    setupGeminiLive()
    setupChat()
    setupObservers()
    setupClickListeners()

    // Start call monitor service
    startService(Intent(this, CallMonitorService::class.java))

    // Register call ended receiver
    ContextCompat.registerReceiver(
      this, callEndedReceiver,
      IntentFilter("com.myra.CALL_ENDED"),
      ContextCompat.RECEIVER_EXPORTED
    )

    // Handle incoming call intent
    if (intent.getBooleanExtra("INCOMING_CALL", false)) {
      val callerName = intent.getStringExtra("CALLER_NAME") ?: "Unknown"
      announceCall(callerName)
    }

    updateSystemInfo()
  }

  private fun initViews() {
    orbView = findViewById(R.id.orbView)
    waveformView = findViewById(R.id.waveformView)
    statusText = findViewById(R.id.statusText)
    micButton = findViewById(R.id.micButton)
    settingsBtn = findViewById(R.id.settingsBtn)
    chatRecycler = findViewById(R.id.chatRecycler)
    redOverlay = findViewById(R.id.redOverlay)
    batteryText = findViewById(R.id.batteryText)
    ramText = findViewById(R.id.ramText)
    timeText = findViewById(R.id.timeText)
  }

  private fun checkPermissions() {
    val permissions = mutableListOf(
      Manifest.permission.RECORD_AUDIO,
      Manifest.permission.CALL_PHONE,
      Manifest.permission.READ_CONTACTS,
      Manifest.permission.READ_PHONE_STATE,
      Manifest.permission.READ_CALL_LOG,
      Manifest.permission.SEND_SMS,
      Manifest.permission.INTERNET
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
      ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSIONS_REQUEST_CODE)
    }
  }

  private fun setupAudioEngine() {
    audioEngine = AudioEngine()
    audioEngine.onAudioChunk = { chunk ->
      if (isActive && !isInCallMode) {
        geminiLive.sendAudioChunk(chunk)
      }
    }
    audioEngine.onAmplitudeChanged = { rms ->
      runOnUiThread {
        waveformView.setAmplitude(rms)
        if (rms > 0.1f) {
          orbView.setState(OrbAnimationView.State.LISTENING)
        }
      }
    }
  }

  private fun setupGeminiLive() {
    geminiLive = GeminiLiveClient(this, prefs)
    geminiLive.onSetupComplete = {
      runOnUiThread {
        audioEngine.start()
        statusText.text = "Connected — Tap to speak"
      }
    }
    geminiLive.onInputTranscript = { text ->
      inputBuffer.append(text)
    }
    geminiLive.onOutputTranscript = { text ->
      outputBuffer.append(text)
      runOnUiThread {
        orbView.setState(OrbAnimationView.State.SPEAKING)
      }
    }
    geminiLive.onAudioData = { data ->
      audioEngine.queueAudio(data)
      audioEngine.setSuppressed(true)
    }
    geminiLive.onTurnComplete = {
      audioEngine.setSuppressed(false)
      runOnUiThread {
        flushTranscripts()
        orbView.setState(OrbAnimationView.State.IDLE)
      }
    }
    geminiLive.onError = { error ->
      runOnUiThread {
        Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
        statusText.text = "Error — Check settings"
      }
    }
    geminiLive.connect()
  }

  private fun setupChat() {
    chatAdapter = ChatAdapter()
    chatRecycler.layoutManager = LinearLayoutManager(this).apply {
      stackFromEnd = true
    }
    chatRecycler.adapter = chatAdapter
  }

  private fun setupObservers() {
    viewModel.commandResult.observe(this) { result ->
      result?.let {
        chatAdapter.addMessage(ChatMessage(it, false))
        geminiLive.sendText(it)
      }
    }
  }

  private fun setupClickListeners() {
    micButton.setOnClickListener {
      if (isMuted) {
        isMuted = false
        audioEngine.setMuted(false)
        micButton.setImageResource(android.R.drawable.ic_btn_speak_now)
        statusText.text = "Tap karke bolo"
      } else {
        isActive = !isActive
        if (isActive) {
          orbView.setState(OrbAnimationView.State.ACTIVE)
          statusText.text = "Listening..."
          showRedOverlay(true)
        } else {
          orbView.setState(OrbAnimationView.State.IDLE)
          statusText.text = "Tap karke bolo"
          showRedOverlay(false)
        }
      }
    }

    micButton.setOnLongClickListener {
      audioEngine.clearPlaybackQueue()
      geminiLive.interrupt()
      orbView.setState(OrbAnimationView.State.IDLE)
      Toast.makeText(this, "Interrupted", Toast.LENGTH_SHORT).show()
      true
    }

    settingsBtn.setOnClickListener {
      startActivity(Intent(this, SettingsActivity::class.java))
    }
  }

  private fun flushTranscripts() {
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
  }

  private fun showRedOverlay(show: Boolean) {
    val anim = AlphaAnimation(
      if (show) 0f else 0.08f,
      if (show) 0.08f else 0f
    )
    anim.duration = if (show) 300 else 500
    anim.fillAfter = true
    redOverlay.startAnimation(anim)
  }

  fun announceCall(callerName: String) {
    isInCallMode = true
    setActiveMode(true)
    val prompt = "Sir, $callerName ka call aa raha hai. Uthau ya reject karu?"
    geminiLive.sendText(prompt)

    handler.postDelayed({
      startCallSTT()
    }, CALL_STT_TIMEOUT_MS)
  }

  private fun startCallSTT() {
    if (!isInCallMode) return
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
        startCallSTT()
      }
      override fun onResults(results: Bundle?) {
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
      }
      override fun onPartialResults(partialResults: Bundle?) {}
      override fun onEvent(eventType: Int, params: Bundle?) {}
    })
    callStt?.startListening(intent)
  }

  private fun setActiveMode(active: Boolean) {
    isActive = active
    showRedOverlay(active)
    if (active) {
      orbView.setState(OrbAnimationView.State.ACTIVE)
    } else {
      orbView.setState(OrbAnimationView.State.IDLE)
    }
  }

  private fun updateSystemInfo() {
    val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: 0
    val scale = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: 100
    val pct = (level * 100 / scale)
    batteryText.text = "BATT: $pct%"

    val memInfo = android.app.ActivityManager.MemoryInfo()
    val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    am.getMemoryInfo(memInfo)
    val availMB = memInfo.availMem / (1024 * 1024)
    ramText.text = "RAM: ${availMB}MB"

    timeText.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
      .format(java.util.Date())

    handler.postDelayed({ updateSystemInfo() }, 30000)
  }

  override fun onNewIntent(intent: Intent?) {
    super.onNewIntent(intent)
    if (intent?.getBooleanExtra("INCOMING_CALL", false) == true) {
      val callerName = intent.getStringExtra("CALLER_NAME") ?: "Unknown"
      announceCall(callerName)
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    geminiLive.disconnect()
    audioEngine.stop()
    callStt?.destroy()
    unregisterReceiver(callEndedReceiver)
    handler.removeCallbacksAndMessages(null)
  }
}
