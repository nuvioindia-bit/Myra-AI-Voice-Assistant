package com.myra.assistant.ui.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.*

class OrbAnimationView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

  enum class State { IDLE, LISTENING, SPEAKING, THINKING, ACTIVE }

  private var currentState = State.IDLE
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 3f
  }
  private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)

  private var pulseScale = 1f
  private var glowAlpha = 120
  private var rotationAngle = 0f
  private var waveOffset = 0f
  private var thinkingAngle = 0f
  private var particleAngle = 0f

  private var pulseAnimator: ValueAnimator? = null
  private var rotationAnimator: ValueAnimator? = null
  private var waveAnimator: ValueAnimator? = null
  private var thinkingAnimator: ValueAnimator? = null

  private val particles = List(12) { index ->
    Particle(
      angle = (index * 30f).toDouble(),
      radius = 0.6f + (index % 3) * 0.15f,
      speed = 0.5f + (index % 4) * 0.2f,
      size = 3f + (index % 3) * 1.5f
    )
  }

  private data class Particle(
    var angle: Double,
    val radius: Float,
    val speed: Float,
    val size: Float
  )

  fun setState(state: State) {
    if (currentState == state) return
    currentState = state
    invalidate()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    startAnimations()
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    stopAnimations()
  }

  private fun startAnimations() {
    pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
      duration = 1500
      repeatCount = ValueAnimator.INFINITE
      repeatMode = ValueAnimator.REVERSE
      interpolator = android.view.animation.AccelerateDecelerateInterpolator()
      addUpdateListener {
        val v = it.animatedValue as Float
        pulseScale = 1f + v * 0.15f
        glowAlpha = (120 + v * 100).toInt()
        invalidate()
      }
      start()
    }

    rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
      duration = 8000
      repeatCount = ValueAnimator.INFINITE
      interpolator = LinearInterpolator()
      addUpdateListener {
        rotationAngle = it.animatedValue as Float
        invalidate()
      }
      start()
    }

    waveAnimator = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
      duration = 3000
      repeatCount = ValueAnimator.INFINITE
      interpolator = LinearInterpolator()
      addUpdateListener {
        waveOffset = it.animatedValue as Float
        invalidate()
      }
      start()
    }

    thinkingAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
      duration = 1500
      repeatCount = ValueAnimator.INFINITE
      interpolator = LinearInterpolator()
      addUpdateListener {
        thinkingAngle = it.animatedValue as Float
        invalidate()
      }
      start()
    }
  }

  private fun stopAnimations() {
    pulseAnimator?.cancel()
    rotationAnimator?.cancel()
    waveAnimator?.cancel()
    thinkingAnimator?.cancel()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val cx = width / 2f
    val cy = height / 2f
    val radius = (min(width, height) / 2f) * 0.85f * pulseScale

    val (startColor, endColor) = when (currentState) {
      State.IDLE -> Pair(Color.parseColor("#B71C1C"), Color.parseColor("#880E4F"))
      State.LISTENING, State.ACTIVE -> Pair(Color.parseColor("#FF1744"), Color.parseColor("#D500F9"))
      State.SPEAKING -> Pair(Color.parseColor("#E040FB"), Color.parseColor("#FF1744"))
      State.THINKING -> Pair(Color.parseColor("#40C4FF"), Color.parseColor("#00B0FF"))
    }

    // 1. Radial glow
    glowPaint.shader = RadialGradient(
      cx, cy, radius * 1.6f,
      intArrayOf(startColor, endColor, Color.TRANSPARENT),
      floatArrayOf(0f, 0.5f, 1f),
      Shader.TileMode.CLAMP
    )
    glowPaint.alpha = glowAlpha
    canvas.drawCircle(cx, cy, radius * 1.6f, glowPaint)

    // 2. Core orb
    paint.shader = RadialGradient(
      cx, cy, radius,
      intArrayOf(startColor, endColor),
      floatArrayOf(0.3f, 1f),
      Shader.TileMode.CLAMP
    )
    canvas.drawCircle(cx, cy, radius, paint)

    // 3. Rotating rings
    if (currentState != State.IDLE) {
      for (i in 0 until 3) {
        val ringRadius = radius * (0.6f + i * 0.2f)
        val dashLen = (2 * PI * ringRadius / 8).toFloat()
        ringPaint.color = startColor
        ringPaint.alpha = 80 + i * 40
        ringPaint.pathEffect = DashPathEffect(floatArrayOf(dashLen, dashLen), rotationAngle * (i + 1))
        canvas.drawCircle(cx, cy, ringRadius, ringPaint)
      }
    }

    // 4. Wave rings
    if (currentState != State.IDLE) {
      ringPaint.color = endColor
      ringPaint.alpha = 100
      ringPaint.pathEffect = null
      val waveRadius = radius * 0.5f
      val wavePath = Path()
      for (a in 0..360 step 5) {
        val rad = Math.toRadians(a.toDouble())
        val wave = sin(rad * 3 + waveOffset) * 10
        val x = (cx + cos(rad) * (waveRadius + wave)).toFloat()
        val y = (cy + sin(rad) * (waveRadius + wave)).toFloat()
        if (a == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
      }
      wavePath.close()
      canvas.drawPath(wavePath, ringPaint)
    }

    // 5. Thinking arc
    if (currentState == State.THINKING) {
      paint.style = Paint.Style.STROKE
      paint.strokeWidth = 6f
      paint.color = Color.parseColor("#00B0FF")
      val thinkRadius = radius * 0.7f
      canvas.drawArc(
        cx - thinkRadius, cy - thinkRadius,
        cx + thinkRadius, cy + thinkRadius,
        thinkingAngle, 120f, false, paint
      )
      canvas.drawArc(
        cx - thinkRadius, cy - thinkRadius,
        cx + thinkRadius, cy + thinkRadius,
        thinkingAngle + 180, 120f, false, paint
      )
      paint.style = Paint.Style.FILL
    }

    // 6. Particles
    if (currentState == State.ACTIVE || currentState == State.SPEAKING) {
      particleAngle += 1f
      for (p in particles) {
        p.angle += p.speed * 0.02
        val px = (cx + cos(p.angle) * radius * p.radius).toFloat()
        val py = (cy + sin(p.angle) * radius * p.radius).toFloat()
        particlePaint.color = startColor
        particlePaint.alpha = 180
        canvas.drawCircle(px, py, p.size, particlePaint)
      }
    }

    // 7. Inner highlight
    highlightPaint.shader = RadialGradient(
      cx - radius * 0.2f, cy - radius * 0.2f, radius * 0.5f,
      intArrayOf(Color.WHITE, Color.TRANSPARENT),
      floatArrayOf(0f, 1f),
      Shader.TileMode.CLAMP
    )
    highlightPaint.alpha = 60
    canvas.drawCircle(cx, cy, radius, highlightPaint)
  }
}
