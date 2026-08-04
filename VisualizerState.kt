package com.example.app_abdelbaset

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the entire Orb system.
 * Thread-safe — written from audio/AI threads, read from GL thread.
 */
object VisualizerState {

    enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING }

    private val _orbState = MutableStateFlow(OrbState.IDLE)
    val orbStateFlow: StateFlow<OrbState> = _orbState.asStateFlow()

    @Volatile var orbState: OrbState = OrbState.IDLE
        private set

    fun setState(s: OrbState) {
        orbState = s
        _orbState.value = s
    }

    // ── Legacy compat ────────────────────────────────────────────────
    fun isActive(): Boolean = orbState != OrbState.IDLE

    // ── Audio data ───────────────────────────────────────────────────
    @Volatile private var _audioLevel = 0f
    @Volatile private var _bass       = 0f
    @Volatile private var _mid        = 0f
    @Volatile private var _treble     = 0f

    fun getAudioLevel() = _audioLevel
    fun getBass()       = _bass
    fun getMid()        = _mid
    fun getTreble()     = _treble

    /** Called by AudioFFTEngine every ~16 ms */
    fun updateAudio(rmsLevel: Float, bands: FloatArray) {
        _audioLevel = rmsLevel.coerceIn(0f, 1f)
        if (bands.size >= 3) {
            _bass   = bands[0].coerceIn(0f, 1f)
            _mid    = bands[1].coerceIn(0f, 1f)
            _treble = bands[2].coerceIn(0f, 1f)
        }
    }

    /**
     * 0=IDLE · 0.6=LISTENING · 0.8=THINKING · 1.0=SPEAKING
     * Used as u_mode target in the shader.
     */
    fun shaderModeTarget(): Float = when (orbState) {
        OrbState.IDLE      -> 0f
        OrbState.LISTENING -> 0.60f
        OrbState.THINKING  -> 0.80f
        OrbState.SPEAKING  -> 1.00f
    }

    fun reset() {
        setState(OrbState.IDLE)
        _audioLevel = 0f; _bass = 0f; _mid = 0f; _treble = 0f
    }
    // ── Convenience API (used by AxonVoiceSession) ───────────────────────
    val instance get() = this

    fun activate()   = setState(OrbState.LISTENING)
    fun deactivate() = setState(OrbState.IDLE)

    fun setListening(v: Boolean) {
        if (v) setState(OrbState.LISTENING)
        else if (orbState == OrbState.LISTENING) setState(OrbState.IDLE)
    }

    fun setSpeaking(v: Boolean) {
        if (v) setState(OrbState.SPEAKING)
        else if (orbState == OrbState.SPEAKING) setState(OrbState.IDLE)
    }
    fun setAudioLevel(level: Float) { _audioLevel = level.coerceIn(0f, 1f) }

    fun getListening() = orbState == OrbState.LISTENING
    fun getSpeaking()  = orbState == OrbState.SPEAKING
}