package com.example.app_abdelbaset

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.*
import kotlin.random.Random

/**
 * VisualizerView — v2 FIXED
 * ══════════════════════════════════════════════════════════════════════
 *
 * FIXES vs v1:
 *
 *  Fix 1 — Wave color array destructuring was wrong
 *    The array was [alpha, r, g, b] but was being used as [r, g, b, a].
 *    Corrected to explicitly index each element.
 *
 *  Fix 2 — Wave draws even at low audio levels
 *    NOISE_THRESHOLD lowered from 0.02f → 0.005f so even quiet TTS
 *    audio produces a visible wave.
 *
 *  Fix 3 — Size uses actual measured width/height
 *    Instead of a fixed 300dp size baked in, now uses onSizeChanged()
 *    so the view always fills its allocated space correctly.
 *
 * ══════════════════════════════════════════════════════════════════════
 */
class VisualizerView(context: Context) : View(context) {

    // ── Size (set from onSizeChanged) ─────────────────────────────────
    private var W = 0f
    private var H = 0f

    // ── Animation state ───────────────────────────────────────────────
    private var phase      = 0f
    private var scanOffset = 0f
    private var hudAngle   = 0f

    // ── Particles  [x, y, speed] ──────────────────────────────────────
    private val PARTICLE_COUNT = 60
    private val particles = Array(PARTICLE_COUNT) { floatArrayOf(0f, 0f, Random.nextFloat() * 3f + 1f) }
    private var particlesInitialized = false

    // ── Paints ────────────────────────────────────────────────────────
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 0, 255, 150)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val scanPaint = Paint().apply {
        color = Color.argb(25, 0, 255, 150)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 255, 255)
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val clipPath = Path()
    private val wavePath = Path()

    // ── Animator ──────────────────────────────────────────────────────
    private var animator: ValueAnimator? = null

    init {
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        W = w.toFloat()
        H = h.toFloat()

        // Init particles now that we know the size
        if (!particlesInitialized && W > 0 && H > 0) {
            for (p in particles) {
                p[0] = Random.nextFloat() * W
                p[1] = Random.nextFloat() * H
            }
            particlesInitialized = true
        }

        startAnimatorIfNeeded()
    }

    private fun startAnimatorIfNeeded() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = Long.MAX_VALUE
            repeatCount  = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { tick() }
            start()
        }
    }

    private fun tick() {
        if (W == 0f) return
        val state      = VisualizerState.instance
        val audioLevel = state.getAudioLevel()

        phase      += 0.05f
        scanOffset  = (scanOffset + 2f) % H
        hudAngle    = (hudAngle + 0.3f) % 360f

        for (p in particles) {
            p[1] -= p[2] * (0.5f + audioLevel * 5f)
            if (p[1] < 0f) p[1] = H
        }

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (W == 0f || H == 0f) return

        val state       = VisualizerState.instance
        val audioLevel  = state.getAudioLevel()
        val isSpeaking  = state.getSpeaking()
        val isListening = state.getListening()
        val cx          = W / 2f
        val cy          = H / 2f
        val radius      = minOf(W, H) / 2f

        // ── Clip to circle ────────────────────────────────────────────
        clipPath.reset()
        clipPath.addCircle(cx, cy, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)

        // ── 1. Background ─────────────────────────────────────────────
        canvas.drawCircle(cx, cy, radius, bgPaint)

        // ── 2. HUD Grid (rotating) ────────────────────────────────────
        canvas.save()
        canvas.rotate(hudAngle, cx, cy)
        var r = 30f
        while (r < radius) {
            canvas.drawCircle(cx, cy, r, gridPaint)
            r += 30f
        }
        for (deg in 0 until 360 step 30) {
            val rad = Math.toRadians(deg.toDouble())
            val cos = cos(rad).toFloat()
            val sin = sin(rad).toFloat()
            canvas.drawLine(
                cx + cos * 12f, cy + sin * 12f,
                cx + cos * radius, cy + sin * radius,
                gridPaint
            )
        }
        canvas.restore()

        // ── 3. Scanlines ──────────────────────────────────────────────
        var sy = 0f
        while (sy < H) {
            val yy = (sy + scanOffset) % H
            canvas.drawLine(0f, yy, W, yy, scanPaint)
            sy += 6f
        }

        // ── 4. Particles ──────────────────────────────────────────────
        for (p in particles) {
            canvas.drawPoint(p[0], p[1], particlePaint)
        }

        // ── 5. Wave — 3 layers ────────────────────────────────────────
        // FIX 1: explicit [r, g, b, a] — no destructuring confusion
        data class WaveLayer(val r: Int, val g: Int, val b: Int, val a: Int, val stroke: Float, val idx: Int)

        val layers = listOf(
            WaveLayer(0, 255, 200, 200, 4f, 0),
            WaveLayer(0, 180, 255, 150, 3f, 1),
            WaveLayer(150, 0, 255, 120, 2f, 2)
        )

        // FIX 2: lower threshold so quiet TTS still animates
        val NOISE_THRESHOLD = 0.005f
        val BASE_AMP        = 8f
        val MAX_AMP         = 55f

        for (layer in layers) {
            wavePath.reset()

            if (isSpeaking && audioLevel > NOISE_THRESHOLD) {
                val amp = BASE_AMP + audioLevel * (MAX_AMP - BASE_AMP) * (0.8f - layer.idx * 0.2f)
                var first = true
                var x = 0f
                while (x <= W) {
                    val wy = cy + sin((x / (20f + layer.idx * 5f)) + phase) * amp
                    if (first) { wavePath.moveTo(x, wy); first = false }
                    else wavePath.lineTo(x, wy)
                    x += 2f   // step of 2px for smooth curve
                }
            } else {
                // Flat idle line
                wavePath.moveTo(0f, cy)
                wavePath.lineTo(W, cy)
            }

            // Main line
            wavePaint.color       = Color.argb(layer.a, layer.r, layer.g, layer.b)
            wavePaint.strokeWidth = layer.stroke
            canvas.drawPath(wavePath, wavePaint)

            // Glow layer (wider + transparent)
            wavePaint.color       = Color.argb(50, layer.r, layer.g, layer.b)
            wavePaint.strokeWidth = layer.stroke + 6f
            canvas.drawPath(wavePath, wavePaint)
        }

        // ── 6. Status dot ─────────────────────────────────────────────
        when {
            isSpeaking -> {
                dotPaint.color = Color.argb(230, 0, 255, 100)
                canvas.drawCircle(cx, cy, 7f, dotPaint)
                // Outer pulse ring
                dotPaint.color = Color.argb(80, 0, 255, 100)
                dotPaint.style = Paint.Style.STROKE
                (dotPaint as Paint).strokeWidth = 2f
                canvas.drawCircle(cx, cy, 14f, dotPaint)
                dotPaint.style = Paint.Style.FILL
            }
            isListening -> {
                dotPaint.color = Color.argb(230, 255, 200, 0)
                canvas.drawCircle(cx, cy, 7f, dotPaint)
                dotPaint.color = Color.argb(80, 255, 200, 0)
                dotPaint.style = Paint.Style.STROKE
                (dotPaint as Paint).strokeWidth = 2f
                canvas.drawCircle(cx, cy, 14f, dotPaint)
                dotPaint.style = Paint.Style.FILL
            }
        }

        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }
}