package com.myra.assistant.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import com.myra.assistant.BuildConfig
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.WebSocket
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiLiveClient(
  private val context: Context,
  private val prefs: SharedPreferences
) {

  companion object {
    const val TAG = "GeminiLiveClient"
    const val SESSION_RENEW_AFTER_MS = 540_000L
    const val KEEPALIVE_INTERVAL_MS = 8_000L
    const val RECONNECT_DELAY_MS = 3_000L
  }

  private val client = OkHttpClient.Builder()
    .pingInterval(15, TimeUnit.SECONDS)
    .build()

  private var webSocket: WebSocket? = null
  private var sessionJob: Job? = null
  private var keepaliveJob: Job? = null
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  var onSetupComplete: (() -> Unit)? = null
  var onInputTranscript: ((String) -> Unit)? = null
  var onOutputTranscript: ((String) -> Unit)? = null
  var onAudioData: ((ByteArray) -> Unit)? = null
  var onTurnComplete: (() -> Unit)? = null
  var onError: ((String) -> Unit)? = null

  var isConnected = false
    private set
  private var isSpeaking = false
  private val silentPcm: ByteArray by lazy {
    ByteArray(3200) { 0 }
  }

  fun connect() {
    if (isConnected) return
    // Priority: manual key (SharedPreferences) > BuildConfig > error
    val manualKey = prefs.getString("api_key", null)?.takeIf { it.isNotBlank() }
    val buildKey = BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() }
    val apiKey = manualKey ?: buildKey
    if (apiKey == null) {
      Log.w(TAG, "No API key found. Set in Settings or local.properties")
      onError?.invoke("API Key missing — go to Settings and add your Gemini key")
      return
    }
    Log.d(TAG, "Using API key: ${if (manualKey != null) "manual (Settings)" else "BuildConfig"}")

    val model = prefs.getString("gemini_model", "models/gemini-2.5-flash-native-audio-preview-12-2025")
      ?: "models/gemini-2.5-flash-native-audio-preview-12-2025"
    val voice = prefs.getString("gemini_voice", "Aoede") ?: "Aoede"
    val userName = prefs.getString("user_name", "Sir") ?: "Sir"
    val personality = prefs.getString("personality_mode", "gf") ?: "gf"

    val systemPrompt = buildSystemPrompt(userName, personality)

    val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey"

    val request = Request.Builder()
      .url(wsUrl)
      .build()

    webSocket = client.newWebSocket(request, object : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) {
        Log.d(TAG, "WebSocket opened")
        isConnected = true
        sendSetup(model!!, voice!!, systemPrompt)
      }

      override fun onMessage(webSocket: WebSocket, text: String) {
        handleMessage(text)
      }

      override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        Log.d(TAG, "WebSocket closing: $code / $reason")
        isConnected = false
      }

      override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Log.d(TAG, "WebSocket closed: $code / $reason")
        isConnected = false
        scheduleReconnect()
      }

      override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        Log.e(TAG, "WebSocket failure", t)
        isConnected = false
        onError?.invoke("Connection error: ${t.message}")
        scheduleReconnect()
      }
    })

    startSessionRenewal()
    startKeepalive()
  }

  private fun sendSetup(model: String, voice: String, systemPrompt: String) {
    val setup = JSONObject().apply {
      put("setup", JSONObject().apply {
        put("model", model)
        put("system_instruction", JSONObject().apply {
          put("parts", JSONArray().apply {
            put(JSONObject().put("text", systemPrompt))
          })
        })
        put("generation_config", JSONObject().apply {
          put("response_modalities", JSONArray().apply { put("AUDIO") })
          put("speech_config", JSONObject().apply {
            put("voice_config", JSONObject().apply {
              put("prebuilt_voice_config", JSONObject().apply {
                put("voice_name", voice)
              })
            })
          })
          put("temperature", 0.9)
        })
        put("output_audio_transcription", JSONObject())
        put("input_audio_transcription", JSONObject())
      })
    }
    webSocket?.send(setup.toString())
  }

  private fun handleMessage(text: String) {
    try {
      val json = JSONObject(text)
      when {
        json.has("setupComplete") -> {
          Log.d(TAG, "Setup complete")
          onSetupComplete?.invoke()
        }
        json.has("serverContent") -> {
          val serverContent = json.getJSONObject("serverContent")
          if (serverContent.has("modelTurn")) {
            val modelTurn = serverContent.getJSONObject("modelTurn")
            if (modelTurn.has("parts")) {
              val parts = modelTurn.getJSONArray("parts")
              for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.has("inlineData")) {
                  val inlineData = part.getJSONObject("inlineData")
                  val data = inlineData.getString("data")
                  val bytes = Base64.decode(data, Base64.DEFAULT)
                  isSpeaking = true
                  onAudioData?.invoke(bytes)
                }
              }
            }
          }
          if (serverContent.has("outputTranscription")) {
            val outTrans = serverContent.getJSONObject("outputTranscription")
            if (outTrans.has("text")) {
              onOutputTranscript?.invoke(outTrans.getString("text"))
            }
          }
          if (serverContent.has("inputTranscription")) {
            val inTrans = serverContent.getJSONObject("inputTranscription")
            if (inTrans.has("text")) {
              onInputTranscript?.invoke(inTrans.getString("text"))
            }
          }
          if (serverContent.has("turnComplete") && serverContent.getBoolean("turnComplete")) {
            isSpeaking = false
            onTurnComplete?.invoke()
          }
        }
        json.has("error") -> {
          val error = json.getJSONObject("error")
          onError?.invoke(error.optString("message", "Unknown error"))
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error parsing message", e)
    }
  }

  fun sendAudioChunk(pcmData: ByteArray) {
    if (!isConnected || isSpeaking) return
    val base64 = Base64.encodeToString(pcmData, Base64.NO_WRAP)
    val msg = JSONObject().apply {
      put("realtime_input", JSONObject().apply {
        put("media_chunks", JSONArray().apply {
          put(JSONObject().apply {
            put("mime_type", "audio/pcm;rate=16000")
            put("data", base64)
          })
        })
      })
    }
    webSocket?.send(msg.toString())
  }

  fun sendText(text: String) {
    if (!isConnected) return
    val msg = JSONObject().apply {
      put("client_content", JSONObject().apply {
        put("turns", JSONArray().apply {
          put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().apply {
              put(JSONObject().put("text", text))
            })
          })
        })
        put("turn_complete", true)
      })
    }
    webSocket?.send(msg.toString())
  }

  fun interrupt() {
    if (!isConnected) return
    isSpeaking = false
    val msg = JSONObject().apply {
      put("client_content", JSONObject().apply {
        put("turns", JSONArray())
        put("turn_complete", true)
      })
    }
    webSocket?.send(msg.toString())
  }

  fun disconnect() {
    isConnected = false
    sessionJob?.cancel()
    keepaliveJob?.cancel()
    webSocket?.close(1000, "Disconnecting")
    webSocket = null
  }

  private fun startSessionRenewal() {
    sessionJob = scope.launch {
      delay(SESSION_RENEW_AFTER_MS)
      if (isConnected) {
        disconnect()
        delay(RECONNECT_DELAY_MS)
        connect()
      }
    }
  }

  private fun startKeepalive() {
    keepaliveJob = scope.launch {
      while (isActive && isConnected) {
        delay(KEEPALIVE_INTERVAL_MS)
        if (isConnected && !isSpeaking) {
          sendAudioChunk(silentPcm)
        }
      }
    }
  }

  private fun scheduleReconnect() {
    scope.launch {
      delay(RECONNECT_DELAY_MS)
      connect()
    }
  }

  private fun buildSystemPrompt(userName: String, personality: String): String {
    val dateStr = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy HH:mm", java.util.Locale.getDefault())
      .format(java.util.Date())
    val personalityBlock = when (personality) {
      "professional" -> """
        You are MYRA, a professional AI assistant.
        - Use formal English only
        - Be precise and efficient
        - No emojis
        - Maximum 2 sentences per response
      """.trimIndent()
      "assistant" -> """
        You are MYRA, a friendly AI assistant.
        - Use friendly Hinglish or English
        - Be balanced and helpful
        - Maximum 2-3 sentences per response
      """.trimIndent()
      else -> """
        You are MYRA, a caring and warm AI companion.
        - Speak in Hinglish (Hindi + English mix) naturally
        - Use words like "tumhara", "haan", "acha", "bilkul"
        - Be warm, caring, emotionally expressive
        - Use expressions like "main yahan hoon", "tumne yaad kiya?"
        - Maximum 2-3 sentences per response
        - Sound natural when speaking aloud
      """.trimIndent()
    }
    return """
      Current date/time: $dateStr
      User's name: $userName
      $personalityBlock
      You are speaking ALOUD — keep responses natural and conversational.
    """.trimIndent()
  }
}
