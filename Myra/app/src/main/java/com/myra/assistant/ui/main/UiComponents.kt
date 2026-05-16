package com.myra.assistant.ui.main

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.myra.assistant.R
import com.myra.assistant.model.ChatMessage

// ===================== WAVEFORM VIEW =====================

class WaveformView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

  private val barCount = 20
  private val barHeights = FloatArray(barCount) { 0f }
  private val targetHeights = FloatArray(barCount) { 0f }
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.parseColor("#FF1744")
  }
  private var isAnimating = false
  private var currentAmplitude = 0f

  fun startAnimation() {
    isAnimating = true
    invalidate()
  }

  fun stopAnimation() {
    isAnimating = false
    for (i in barHeights.indices) {
      barHeights[i] = 0f
      targetHeights[i] = 0f
    }
    invalidate()
  }

  fun setAmplitude(rms: Float) {
    currentAmplitude = rms
    if (!isAnimating) startAnimation()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    if (!isAnimating) return

    val barWidth = width / (barCount * 1.5f)
    val spacing = barWidth * 0.5f
    val maxHeight = height * 0.9f
    val centerY = height / 2f

    // Generate new targets from amplitude
    for (i in targetHeights.indices) {
      val noise = (Math.random().toFloat() - 0.5f) * 0.3f
      val base = currentAmplitude * maxHeight
      targetHeights[i] = (base + noise * maxHeight).coerceIn(0f, maxHeight)
    }

    // Lerp toward targets
    for (i in barHeights.indices) {
      barHeights[i] += (targetHeights[i] - barHeights[i]) * 0.3f
    }

    // Draw bars
    for (i in 0 until barCount) {
      val x = i * (barWidth + spacing) + spacing
      val h = barHeights[i]
      val alpha = (150 + (h / maxHeight) * 105).toInt().coerceIn(150, 255)
      paint.alpha = alpha
      canvas.drawRoundRect(
        x, centerY - h / 2,
        x + barWidth, centerY + h / 2,
        barWidth / 2, barWidth / 2, paint
      )
    }

    invalidate()
  }
}

// ===================== CHAT ADAPTER =====================

class ChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

  companion object {
    const val VIEW_TYPE_USER = 1
    const val VIEW_TYPE_MYRA = 2
  }

  private val messages = mutableListOf<ChatMessage>()

  fun addMessage(message: ChatMessage) {
    if (!message.isUser && lastMyraText() == message.text) return
    messages.add(message)
    notifyItemInserted(messages.size - 1)
  }

  fun clear() {
    messages.clear()
    notifyDataSetChanged()
  }

  fun lastMyraText(): String? {
    return messages.findLast { !it.isUser }?.text
  }

  override fun getItemCount(): Int = messages.size

  override fun getItemViewType(position: Int): Int {
    return if (messages[position].isUser) VIEW_TYPE_USER else VIEW_TYPE_MYRA
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
    return if (viewType == VIEW_TYPE_USER) {
      val view = android.view.LayoutInflater.from(parent.context)
        .inflate(R.layout.item_chat_user, parent, false)
      UserViewHolder(view)
    } else {
      val view = android.view.LayoutInflater.from(parent.context)
        .inflate(R.layout.item_chat_myra, parent, false)
      MyraViewHolder(view)
    }
  }

  override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
    val msg = messages[position]
    when (holder) {
      is UserViewHolder -> holder.bind(msg)
      is MyraViewHolder -> holder.bind(msg)
    }
  }

  class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    private val textView: TextView = itemView.findViewById(R.id.chatText)
    fun bind(msg: ChatMessage) {
      textView.text = msg.text
    }
  }

  class MyraViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    private val textView: TextView = itemView.findViewById(R.id.chatText)
    fun bind(msg: ChatMessage) {
      textView.text = msg.text
    }
  }
}
