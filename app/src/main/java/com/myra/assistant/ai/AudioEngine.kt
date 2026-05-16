package com.myra.assistant.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue

class AudioEngine {

  companion object {
    const val TAG = "AudioEngine"
    const val MIC_SAMPLE_RATE = 16000
    const val PLAYBACK_SAMPLE_RATE = 24000
    const val BUFFER_SIZE_MIC = 1024
    const val BUFFER_SIZE_PLAYBACK = 1024
  }

  private var audioRecord: AudioRecord? = null
  private var audioTrack: AudioTrack? = null
  private var recordingJob: Job? = null
  private var playbackJob: Job? = null
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val playbackQueue = ConcurrentLinkedQueue<ByteArray>()
  private var isRecording = false
  private var isPlaying = false
  private var isMuted = false
  private var isSuppressed = false

  var onAudioChunk: ((ByteArray) -> Unit)? = null
  var onAmplitudeChanged: ((Float) -> Unit)? = null

  // Context-aware start — permission check pehle
  fun start(context: Context? = null) {
    try {
      // RECORD_AUDIO permission check — crash prevent karo
      if (context != null &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
        Log.w(TAG, "RECORD_AUDIO permission not granted — skipping AudioRecord init")
        startPlayback() // sirf playback start karo
        return
      }
      startRecording()
      startPlayback()
    } catch (e: Exception) {
      Log.e(TAG, "Error starting audio engine", e)
      try { startPlayback() } catch (e2: Exception) { Log.e(TAG, "Playback also failed", e2) }
    }
  }

  fun stop() {
    try { stopRecording() } catch (e: Exception) { Log.e(TAG, "Error stopping recording", e) }
    try { stopPlayback() } catch (e: Exception) { Log.e(TAG, "Error stopping playback", e) }
  }

  private fun startRecording() {
    if (isRecording) return
    try {
      val minBuf = AudioRecord.getMinBufferSize(
        MIC_SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
      )
      if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
        Log.e(TAG, "Invalid AudioRecord buffer size: $minBuf")
        return
      }
      val bufSize = minBuf.coerceAtLeast(BUFFER_SIZE_MIC * 2)
      val ar = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MIC_SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        bufSize
      )
      if (ar.state != AudioRecord.STATE_INITIALIZED) {
        Log.e(TAG, "AudioRecord not initialized — state=${ar.state}")
        ar.release()
        return
      }
      audioRecord = ar
      ar.startRecording()
      isRecording = true

      recordingJob = scope.launch {
        val buffer = ByteArray(BUFFER_SIZE_MIC)
        while (isActive && isRecording) {
          try {
            if (isMuted || isSuppressed) { delay(50); continue }
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            if (read > 0) {
              val chunk = buffer.copyOf(read)
              onAudioChunk?.invoke(chunk)
              val rms = calculateRms(chunk)
              onAmplitudeChanged?.invoke(rms)
            }
          } catch (e: Exception) {
            Log.e(TAG, "Recording loop error", e)
            delay(100)
          }
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "startRecording failed", e)
    }
  }

  private fun startPlayback() {
    if (isPlaying) return
    try {
      val minBuf = AudioTrack.getMinBufferSize(
        PLAYBACK_SAMPLE_RATE,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT
      )
      if (minBuf == AudioTrack.ERROR || minBuf == AudioTrack.ERROR_BAD_VALUE) {
        Log.e(TAG, "Invalid AudioTrack buffer size: $minBuf")
        return
      }
      val at = AudioTrack.Builder()
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        )
        .setAudioFormat(
          AudioFormat.Builder()
            .setSampleRate(PLAYBACK_SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        )
        .setBufferSizeInBytes(minBuf.coerceAtLeast(BUFFER_SIZE_PLAYBACK * 2))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

      if (at.state != AudioTrack.STATE_INITIALIZED) {
        Log.e(TAG, "AudioTrack not initialized — state=${at.state}")
        at.release()
        return
      }
      audioTrack = at
      at.play()
      isPlaying = true

      playbackJob = scope.launch {
        while (isActive && isPlaying) {
          try {
            val chunk = playbackQueue.poll()
            if (chunk != null && chunk.isNotEmpty()) {
              audioTrack?.write(chunk, 0, chunk.size)
              val rms = calculateRms(chunk)
              onAmplitudeChanged?.invoke(rms)
            } else {
              delay(10)
            }
          } catch (e: Exception) {
            Log.e(TAG, "Playback loop error", e)
            delay(100)
          }
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "startPlayback failed", e)
    }
  }

  private fun stopRecording() {
    isRecording = false
    recordingJob?.cancel()
    try { audioRecord?.stop() } catch (e: Exception) { Log.e(TAG, "stop record error", e) }
    try { audioRecord?.release() } catch (e: Exception) { Log.e(TAG, "release record error", e) }
    audioRecord = null
  }

  private fun stopPlayback() {
    isPlaying = false
    playbackJob?.cancel()
    playbackQueue.clear()
    try { audioTrack?.stop() } catch (e: Exception) { Log.e(TAG, "stop track error", e) }
    try { audioTrack?.release() } catch (e: Exception) { Log.e(TAG, "release track error", e) }
    audioTrack = null
  }

  fun queueAudio(data: ByteArray) {
    try { playbackQueue.offer(data) } catch (e: Exception) { Log.e(TAG, "queueAudio error", e) }
  }

  fun clearPlaybackQueue() {
    try {
      playbackQueue.clear()
      audioTrack?.stop()
      audioTrack?.play()
    } catch (e: Exception) { Log.e(TAG, "clearPlaybackQueue error", e) }
  }

  fun setMuted(muted: Boolean) { isMuted = muted }
  fun setSuppressed(suppressed: Boolean) { isSuppressed = suppressed }

  private fun calculateRms(data: ByteArray): Float {
    if (data.size < 2) return 0f
    var sum = 0.0
    var i = 0
    while (i < data.size - 1) {
      val sample = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
      val signed = if (sample > 32767) sample - 65536 else sample
      sum += signed * signed
      i += 2
    }
    val mean = sum / (data.size / 2)
    val rms = kotlin.math.sqrt(mean).toFloat()
    return (rms / 32768f).coerceIn(0f, 1f)
  }
}


class AudioEngine {

  companion object {
    const val TAG = "AudioEngine"
    const val MIC_SAMPLE_RATE = 16000
    const val PLAYBACK_SAMPLE_RATE = 24000
    const val BUFFER_SIZE_MIC = 1024
    const val BUFFER_SIZE_PLAYBACK = 1024
  }

  private var audioRecord: AudioRecord? = null
  private var audioTrack: AudioTrack? = null
  private var recordingJob: Job? = null
  private var playbackJob: Job? = null
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val playbackQueue = ConcurrentLinkedQueue<ByteArray>()
  private var isRecording = false
  private var isPlaying = false
  private var isMuted = false
  private var isSuppressed = false

  var onAudioChunk: ((ByteArray) -> Unit)? = null
  var onAmplitudeChanged: ((Float) -> Unit)? = null

  fun start() {
    startRecording()
    startPlayback()
  }

  fun stop() {
    stopRecording()
    stopPlayback()
  }

  private fun startRecording() {
    if (isRecording) return
    val minBufferSize = AudioRecord.getMinBufferSize(
      MIC_SAMPLE_RATE,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT
    )
    audioRecord = AudioRecord(
      MediaRecorder.AudioSource.VOICE_RECOGNITION,
      MIC_SAMPLE_RATE,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
      minBufferSize.coerceAtLeast(BUFFER_SIZE_MIC * 2)
    )
    audioRecord?.startRecording()
    isRecording = true

    recordingJob = scope.launch {
      val buffer = ByteArray(BUFFER_SIZE_MIC)
      while (isActive && isRecording) {
        if (isMuted || isSuppressed) {
          delay(50)
          continue
        }
        val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
        if (read > 0) {
          val chunk = buffer.copyOf(read)
          onAudioChunk?.invoke(chunk)
          val rms = calculateRms(chunk)
          onAmplitudeChanged?.invoke(rms)
        }
      }
    }
  }

  private fun startPlayback() {
    if (isPlaying) return
    val minBufferSize = AudioTrack.getMinBufferSize(
      PLAYBACK_SAMPLE_RATE,
      AudioFormat.CHANNEL_OUT_MONO,
      AudioFormat.ENCODING_PCM_16BIT
    )
    audioTrack = AudioTrack.Builder()
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ASSISTANT)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build()
      )
      .setAudioFormat(
        AudioFormat.Builder()
          .setSampleRate(PLAYBACK_SAMPLE_RATE)
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
          .build()
      )
      .setBufferSizeInBytes(minBufferSize.coerceAtLeast(BUFFER_SIZE_PLAYBACK * 2))
      .setTransferMode(AudioTrack.MODE_STREAM)
      .build()
    audioTrack?.play()
    isPlaying = true

    playbackJob = scope.launch {
      val buffer = ByteArray(BUFFER_SIZE_PLAYBACK)
      while (isActive && isPlaying) {
        val chunk = playbackQueue.poll()
        if (chunk != null && chunk.isNotEmpty()) {
          audioTrack?.write(chunk, 0, chunk.size)
          val rms = calculateRms(chunk)
          onAmplitudeChanged?.invoke(rms)
        } else {
          delay(10)
        }
      }
    }
  }

  private fun stopRecording() {
    isRecording = false
    recordingJob?.cancel()
    audioRecord?.stop()
    audioRecord?.release()
    audioRecord = null
  }

  private fun stopPlayback() {
    isPlaying = false
    playbackJob?.cancel()
    playbackQueue.clear()
    audioTrack?.stop()
    audioTrack?.release()
    audioTrack = null
  }

  fun queueAudio(data: ByteArray) {
    playbackQueue.offer(data)
  }

  fun clearPlaybackQueue() {
    playbackQueue.clear()
    audioTrack?.stop()
    audioTrack?.play()
  }

  fun setMuted(muted: Boolean) {
    isMuted = muted
  }

  fun setSuppressed(suppressed: Boolean) {
    isSuppressed = suppressed
  }

  private fun calculateRms(data: ByteArray): Float {
    if (data.size < 2) return 0f
    var sum = 0.0
    var i = 0
    while (i < data.size - 1) {
      val sample = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
      val signed = if (sample > 32767) sample - 65536 else sample
      sum += signed * signed
      i += 2
    }
    val mean = sum / (data.size / 2)
    val rms = kotlin.math.sqrt(mean).toFloat()
    return (rms / 32768f).coerceIn(0f, 1f)
  }
}
