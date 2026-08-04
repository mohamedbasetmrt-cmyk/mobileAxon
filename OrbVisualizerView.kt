package com.example.app_abdelbaset

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@SuppressLint("ViewConstructor")
class OrbVisualizerView(context: Context) : View(context), OrbMode {
    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private val runtimeShader = RuntimeShader(OrbShaders.ORB_SHADER)
    private val shaderPaint   = Paint().apply {
        shader = runtimeShader
        isAntiAlias = true
    }

    private val physics = OrbPhysics()

    private var startNs   = System.nanoTime()
    private var lastNs    = startNs

    private var modeSmooth   = 0f
    private var radiusSmooth = ORB_RADIUS_IDLE

    companion object {
        private const val ORB_RADIUS_IDLE   = 0.30f
        private const val ORB_RADIUS_ACTIVE = 0.60f
    }

    override fun setMode(t: Float) {
        val clamped = t.coerceIn(0f, 1f)
        val state = VisualizerState.orbState
        val shaderTarget = when {
            clamped < 0.01f -> 0f
            state == VisualizerState.OrbState.THINKING  -> 0.80f
            state == VisualizerState.OrbState.LISTENING -> 0.60f
            else -> clamped
        }
        modeSmooth += (shaderTarget - modeSmooth) * 0.12f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!canvas.isHardwareAccelerated) {
            canvas.drawColor(android.graphics.Color.TRANSPARENT)
            invalidate()
            return
        }
        val nowNs = System.nanoTime()
        val dt    = ((nowNs - lastNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        lastNs    = nowNs
        val t     = (nowNs - startNs) / 1_000_000_000f

        val vs  = VisualizerState
        val vol = if (vs.isActive()) vs.getAudioLevel() else 0f

        physics.breathAmplitude = 0.018f + vol * 0.025f
        physics.update(dt)

        val targetRadius = ORB_RADIUS_IDLE + (ORB_RADIUS_ACTIVE - ORB_RADIUS_IDLE) * modeSmooth
        radiusSmooth += (targetRadius - radiusSmooth) * 0.10f

        runtimeShader.setFloatUniform("u_time",       t)
        runtimeShader.setFloatUniform("u_resolution", width.toFloat(), height.toFloat())
        runtimeShader.setFloatUniform("u_mode",       modeSmooth)
        runtimeShader.setFloatUniform("u_bass",       vs.getBass())
        runtimeShader.setFloatUniform("u_mid",        vs.getMid())
        runtimeShader.setFloatUniform("u_treble",     vs.getTreble())
        runtimeShader.setFloatUniform("u_volume",     vol)
        runtimeShader.setFloatUniform("u_radius",     radiusSmooth)
        runtimeShader.setFloatUniform("u_breath",     physics.breath)
        runtimeShader.setFloatUniform("u_turbulence", physics.turbulence)

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shaderPaint)

        invalidate()
    }
}