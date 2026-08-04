package com.example.app_abdelbaset

import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/**
 * SimpleOrbView — Expand-on-Tap Edition (FIXED v2)
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Bars are ALWAYS centered in the 50dp orb area (right side)
 * Text appears in the expanded area (left side)
 */
class SimpleOrbView(context: Context) : View(context), OrbMode {

    companion object {
        private const val ORB_SIZE_DP         = 50f
        private const val EXPANDED_WIDTH_DP   = 250f
        private const val BAR_WIDTH_DP        = 3.5f
        private const val BAR_SPACING_DP      = 2.5f
        private const val EDGE_STROKE_DP      = 0.5f
        private const val GLOW_BLUR_DP        = 3f
        private const val INSET_BLUR_DP       = 2f
        private const val TEXT_PADDING_DP     = 12f
        private const val TEXT_SIZE_DP        = 11f
        private const val ANIM_DURATION_MS    = 250L
        private const val COLLAPSE_DURATION_MS = 200L
        private const val AUTO_COLLAPSE_MS    = 5000L

        private val BODY_CENTER = Color.rgb(0x3A, 0x3D, 0x3D)
        private val BODY_EDGE   = Color.rgb(0x32, 0x32, 0x34)
        private val BAR_COLOR   = Color.rgb(0xFE, 0xFC, 0xFF)
        private val TEXT_COLOR  = Color.rgb(0xCC, 0xCC, 0xCC)

        private val DEFAULT_HEIGHTS = floatArrayOf(0.28f, 0.70f, 0.55f, 0.28f)
    }

    private val density = resources.displayMetrics.density
    private val px: (Float) -> Float = { it * density }

    enum class OrbState { CLOSED, EXPANDED }
    private var currentState = OrbState.CLOSED
    private var animProgress = 0f
    private var animStartTime = 0L
    private var animDuration = ANIM_DURATION_MS
    private var isAnimating = false
    private var targetState = OrbState.CLOSED

    private val autoCollapseHandler = Handler(Looper.getMainLooper())
    private val autoCollapseRunnable = Runnable {
        if (currentState == OrbState.EXPANDED) collapse()
    }

    private var displayText = ""
    var onExpansionChanged: ((expanded: Boolean) -> Unit)? = null
    private var textAlpha = 0f

    // Layout
    private var barLefts   = FloatArray(4)
    private var barWidthPx = 0f
    private var barRadiusPx = 0f
    private var orbSizePx = 0f
    private var expandedWidthPx = 0f
    private var textPaddingPx = 0f

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 0, 0, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            maskFilter = BlurMaskFilter(px(3f), BlurMaskFilter.Blur.NORMAL)
        }
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = px(EDGE_STROKE_DP)
        color = Color.argb(13, 255, 255, 255)
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BAR_COLOR }
    private val barGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(38, 0xFE, 0xFC, 0xFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            maskFilter = BlurMaskFilter(px(GLOW_BLUR_DP), BlurMaskFilter.Blur.NORMAL)
        }
    }
    private val barInsetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(26, 0, 0, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            maskFilter = BlurMaskFilter(px(INSET_BLUR_DP), BlurMaskFilter.Blur.NORMAL)
        }
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_COLOR
        textSize = px(TEXT_SIZE_DP)
        typeface = Typeface.MONOSPACE
    }
    private val textGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 0x22, 0xD0, 0x7A)
        textSize = px(TEXT_SIZE_DP)
        typeface = Typeface.MONOSPACE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            maskFilter = BlurMaskFilter(px(4f), BlurMaskFilter.Blur.NORMAL)
        }
    }

    private var drawStartTime = 0L
    private var validTouch = false

    init {
        drawStartTime = System.nanoTime()
        isClickable = true
        isFocusable = true
    }

    // ═══════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═══════════════════════════════════════════════════════════════════

    fun setText(text: String) {
        displayText = text
        if (text.isNotEmpty() && currentState == OrbState.CLOSED) {
            expand()
        } else {
            invalidate()
        }
    }

    fun clearText() {
        displayText = ""
        textAlpha = 0f
        if (currentState == OrbState.EXPANDED) collapse()
    }

    fun expand() {
        if (currentState == OrbState.CLOSED) startAnimation(OrbState.EXPANDED)
    }

    fun collapse() {
        if (currentState == OrbState.EXPANDED) startAnimation(OrbState.CLOSED)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  TOUCH
    // ═══════════════════════════════════════════════════════════════════

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val viewRight = width.toFloat()
        val orbRightEdge = viewRight - edgePaddingPx(viewRight)
        val orbLeftEdge = orbRightEdge - orbSizePx
        val orbCx = (orbLeftEdge + orbRightEdge) / 2f
        val orbCy = height / 2f
        val orbRadius = orbSizePx / 2f

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val dx = x - orbCx
                val dy = y - orbCy
                validTouch = sqrt(dx * dx + dy * dy) <= orbRadius
                return validTouch
            }
            MotionEvent.ACTION_UP -> {
                if (validTouch) {
                    validTouch = false
                    toggleState()
                    return true
                }
                return false
            }
        }
        return super.onTouchEvent(event)
    }

    private fun toggleState() {
        if (currentState == OrbState.CLOSED) {
            if (displayText.isNotEmpty()) startAnimation(OrbState.EXPANDED)
        } else {
            startAnimation(OrbState.CLOSED)
        }
    }

    private fun startAnimation(target: OrbState) {
        targetState = target
        animDuration = if (target == OrbState.EXPANDED) ANIM_DURATION_MS else COLLAPSE_DURATION_MS
        animStartTime = System.currentTimeMillis()
        isAnimating = true
        if (target == OrbState.EXPANDED) onExpansionChanged?.invoke(true)

        if (target == OrbState.EXPANDED) {
            autoCollapseHandler.removeCallbacks(autoCollapseRunnable)
            autoCollapseHandler.postDelayed(autoCollapseRunnable, AUTO_COLLAPSE_MS)
        } else {
            autoCollapseHandler.removeCallbacks(autoCollapseRunnable)
        }
        invalidate()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  LAYOUT — FIXED: Bars centered in orb area (right side)
    // ═══════════════════════════════════════════════════════════════════

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        orbSizePx = px(ORB_SIZE_DP)
        expandedWidthPx = px(EXPANDED_WIDTH_DP)
        textPaddingPx = px(TEXT_PADDING_DP)

        barWidthPx  = px(BAR_WIDTH_DP)
        barRadiusPx = barWidthPx / 2f
        val spacing = px(BAR_SPACING_DP)
        val totalBarsW = 4f * barWidthPx + 3f * spacing

        // ← FIX: Bars centered in the ORB area (right side of view)
        // The orb area is the rightmost 50dp of the view
        val orbRightEdge = w.toFloat() - edgePaddingPx(w.toFloat())
        val orbLeftEdge = orbRightEdge - orbSizePx
        val orbCenterX = (orbLeftEdge + orbRightEdge) / 2f

        // Center bars in the orb area
        val barsStartX = orbCenterX - totalBarsW / 2f

        for (i in 0..3) {
            barLefts[i] = barsStartX + i * (barWidthPx + spacing)
        }

        // Body paint gradient centered on orb
        val orbCy = h / 2f
        val orbRadius = orbSizePx / 2f

        bodyPaint.shader = RadialGradient(
            orbCenterX, orbCy, orbRadius * 0.6f,
            BODY_CENTER, BODY_EDGE,
            Shader.TileMode.CLAMP,
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    //  DRAWING — FIXED: Text on LEFT, Bars centered in RIGHT orb
    // ═══════════════════════════════════════════════════════════════════

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) { postInvalidateOnAnimation(); return }

        updateAnimation()

        val state = VisualizerState.orbState
        if (state == VisualizerState.OrbState.IDLE && animProgress < 0.01f) {
            postInvalidateOnAnimation()
            return
        }

        val elapsed = System.nanoTime() - drawStartTime
        val heights = computeHeights(state, elapsed)

        // Current dimensions during animation
        val currentWidth = orbSizePx + (expandedWidthPx - orbSizePx) * animProgress
        val currentHeight = orbSizePx

        // The view is anchored to the RIGHT side of the screen
        // The orb (50dp) is always on the right edge of the drawn area
        val viewRight = width.toFloat()
        val viewLeft = viewRight - currentWidth
        val viewTop = (height - currentHeight) / 2f
        val viewBottom = viewTop + currentHeight

        // Orb center (right side, always 50dp)
        val orbRightEdge = viewRight - edgePaddingPx(viewRight)
        val orbLeftEdge = orbRightEdge - orbSizePx
        val orbCx = (orbLeftEdge + orbRightEdge) / 2f
        val orbCy = height / 2f
        val orbRadius = orbSizePx / 2f

        // Draw shadow
//        shadowPaint.color = Color.BLACK
        if (animProgress > 0.01f) {
            val shadowRect = RectF(
                viewLeft + px(2f),
                viewTop + px(2f),
                viewRight + px(2f),
                viewBottom + px(2f)
            )
            canvas.drawRoundRect(shadowRect, orbRadius, orbRadius, shadowPaint)
        } else {
            canvas.drawCircle(orbCx + px(2f), orbCy + px(2f), orbRadius, shadowPaint)
        }

        // Draw body
        if (animProgress > 0.01f) {
            val bodyRect = RectF(viewLeft, viewTop, viewRight, viewBottom)
            canvas.drawRoundRect(bodyRect, orbRadius, orbRadius, bodyPaint)
            canvas.drawRoundRect(bodyRect, orbRadius, orbRadius, edgePaint)
        } else {
            canvas.drawCircle(orbCx, orbCy, orbRadius, bodyPaint)
            canvas.drawCircle(orbCx, orbCy, orbRadius - px(EDGE_STROKE_DP) / 2f, edgePaint)
        }

        // Draw bars (centered in the orb area on the right)
        val innerBottom = orbCy + currentHeight * 0.40f
        val innerTop    = orbCy - currentHeight * 0.40f
        val maxBarH     = innerBottom - innerTop

        for (i in 0..3) {
            val barH = heights[i] * maxBarH
            val barTop    = orbCy - barH / 2f
            val barBottom = orbCy + barH / 2f
            val left  = barLefts[i]
            val right = left + barWidthPx

            canvas.drawRoundRect(left, barTop, right, barBottom,
                barRadiusPx, barRadiusPx, barGlowPaint)

            val insetH = barH * 0.18f
            canvas.drawRoundRect(left, barTop, right, barTop + insetH,
                barRadiusPx, barRadiusPx, barInsetPaint)

            canvas.drawRoundRect(left, barTop, right, barBottom,
                barRadiusPx, barRadiusPx, barPaint)

            val hlH = barH * 0.06f
            barInsetPaint.color = Color.argb(8, 255, 255, 255)
            canvas.drawRoundRect(left, barBottom - hlH, right, barBottom,
                barRadiusPx, barRadiusPx, barInsetPaint)
            barInsetPaint.color = Color.argb(26, 0, 0, 0)
        }

        // Draw text on the LEFT side
        if (textAlpha > 0.01f && displayText.isNotEmpty()) {
            val textX = viewLeft + textPaddingPx
            val textY = orbCy + textPaint.textSize / 3f

            textPaint.alpha = (textAlpha * 255).toInt()
            textGlowPaint.alpha = (textAlpha * 60).toInt()

            val maxTextWidth = currentWidth - orbSizePx - textPaddingPx * 2f - px(8f)
            val measuredText = if (maxTextWidth > 0) {
                val textWidth = textPaint.measureText(displayText)
                if (textWidth > maxTextWidth) {
                    var truncated = displayText
                    while (truncated.isNotEmpty() && textPaint.measureText("$truncated...") > maxTextWidth) {
                        truncated = truncated.dropLast(1)
                    }
                    "$truncated..."
                } else {
                    displayText
                }
            } else ""

            if (measuredText.isNotEmpty()) {
                canvas.drawText(measuredText, textX, textY, textGlowPaint)
                canvas.drawText(measuredText, textX, textY, textPaint)
            }
        }

        postInvalidateOnAnimation()
    }
    private fun edgePaddingPx(viewWidth: Float): Float =
        min(px(4f), (viewWidth - orbSizePx).coerceAtLeast(0f))
    private fun updateAnimation() {
        if (isAnimating) {
            val elapsed = System.currentTimeMillis() - animStartTime
            val progress = (elapsed.toFloat() / animDuration).coerceIn(0f, 1f)
            animProgress = if (targetState == OrbState.EXPANDED) {
                decelerate(progress)
            } else {
                1f - decelerate(progress)
            }

            if (progress >= 1f) {
                isAnimating = false
                currentState = targetState
                animProgress = if (currentState == OrbState.EXPANDED) 1f else 0f
                if (currentState == OrbState.CLOSED) onExpansionChanged?.invoke(false)
            }

            textAlpha = if (targetState == OrbState.EXPANDED) {
                if (progress > 0.6f) ((progress - 0.6f) / 0.4f).coerceIn(0f, 1f) else 0f
            } else {
                (1f - progress * 3f).coerceIn(0f, 1f)
            }

            invalidate()
        } else {
            animProgress = if (currentState == OrbState.EXPANDED) 1f else 0f
            textAlpha = if (currentState == OrbState.EXPANDED) 1f else 0f
        }
    }

    private fun decelerate(t: Float): Float = 1f - (1f - t) * (1f - t)

    private fun computeHeights(state: VisualizerState.OrbState, elapsedNanos: Long): FloatArray {
        val elapsedMs = elapsedNanos / 1_000_000
        return when (state) {
            VisualizerState.OrbState.IDLE -> DEFAULT_HEIGHTS
            VisualizerState.OrbState.LISTENING -> listeningHeights(elapsedMs)
            VisualizerState.OrbState.THINKING -> thinkingHeights(elapsedMs)
            VisualizerState.OrbState.SPEAKING -> speakingHeights(elapsedMs)
        }
    }

    private fun listeningHeights(ms: Long): FloatArray {
        val phase = (ms % 4000L).toFloat() / 4000f * 2f * PI.toFloat()
        val pulse = 0.01f * sin(phase.toDouble()).toFloat()
        return FloatArray(4) { i -> (DEFAULT_HEIGHTS[i] + pulse).coerceIn(0f, 1f) }
    }

    private fun thinkingHeights(ms: Long): FloatArray = DEFAULT_HEIGHTS.copyOf()

    private fun speakingHeights(ms: Long): FloatArray {
        val t = ms / 1000f
        return FloatArray(4) { i ->
            val angle = t * (1.8f + i * 0.5f)
            val wave = sin(angle.toDouble()).toFloat()
            (DEFAULT_HEIGHTS[i] + wave * 0.10f).coerceIn(0f, 1f)
        }
    }

    override fun setMode(t: Float) {}

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        autoCollapseHandler.removeCallbacks(autoCollapseRunnable)
    }
}