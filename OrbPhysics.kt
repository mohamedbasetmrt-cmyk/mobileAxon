package com.example.app_abdelbaset

import kotlin.math.*

/**
 * Lightweight spring + inertia physics for the Orb position/scale.
 *
 * Uses a critically-damped spring so the orb "floats" — no oscillation,
 * no overshooting — just organic, heavy mass movement.
 *
 * Update once per frame (call update(dt) with delta-time in seconds).
 */
class OrbPhysics {

    // ── Spring parameters ────────────────────────────────────────────
    private val stiffness   = 180f   // spring K  (higher = snappier)
    private val damping     = 22f    // damping C  (critically damped = 2*sqrt(K*M))
    private val mass        = 1f

    // ── Position spring (normalised -1..1 space) ─────────────────────
    var targetX = 0f; var targetY = 0f     // target position (set externally)
    var posX    = 0f; var posY    = 0f     // current position
    private var velX = 0f; private var velY = 0f

    // ── Scale spring ─────────────────────────────────────────────────
    var targetScale = 1f
    var scale       = 1f
    private var velScale = 0f

    // ── Breathing pulse (always running) ─────────────────────────────
    private var breathPhase = 0f
    var breathAmplitude = 0.018f          // modulated by audio
    var breath = 0f
        private set

    // ── Turbulence (audio-driven secondary motion) ────────────────────
    var turbulence = 0f
        private set
    private var turbPhase = 0f

    fun update(dt: Float) {
        // Spring integration (symplectic Euler)
        val ax = (-stiffness * (posX - targetX) - damping * velX) / mass
        val ay = (-stiffness * (posY - targetY) - damping * velY) / mass
        velX += ax * dt; posX += velX * dt
        velY += ay * dt; posY += velY * dt

        val as_ = (-stiffness * (scale - targetScale) - damping * velScale) / mass
        velScale += as_ * dt; scale += velScale * dt

        // Breathing
        breathPhase += dt * 0.9f   // 0.9 rad/s ≈ very slow breath
        breath = sin(breathPhase) * breathAmplitude

        // Turbulence (faster noise)
        turbPhase += dt * 3.7f
        turbulence = (sin(turbPhase) * 0.5f + sin(turbPhase * 1.73f) * 0.3f) *
                breathAmplitude * 0.4f
    }

    /** Instantly teleport (no animation) */
    fun snap(x: Float, y: Float) {
        posX = x; posY = y; velX = 0f; velY = 0f
    }
}