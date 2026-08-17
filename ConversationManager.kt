package com.example.app_abdelbaset

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean

class ConversationManager(
    private val context: Context,
    private val config: ConversationConfig = ConversationConfig(),
    private val onStateChange: (ConversationState) -> Unit,
    private val onUserUtterance: (String) -> Unit,
    private val onPartialTranscript: (String) -> Unit,
    private val onBargeIn: () -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "ConvManager"
    }

    enum class ConversationState { IDLE, LISTENING, THINKING, SPEAKING, BARGE_IN }

    data class ConversationConfig(
        val minSpeechDurationMs: Long = 200,
        val pauseShortMs: Long = 300,
        val pauseMediumMs: Long = 700,
        val pauseLongMs: Long = 1500,
        val bargeInThresholdMs: Long = 350, // زودناها شوية عشان نتأكد إنه كلام حقيقي مش صدى
        val maxUtteranceMs: Long = 30_000,
        val adaptiveEnabled: Boolean = true,
        val echoCancellationEnabled: Boolean = true,
        val bargeInCooldownMs: Long = 500,   // زودناها عشان نظام الـ AEC يلحق يشتغل
        // ── NEW: تأكيد الـ barge-in (فلترة صدى TTS) ──
        val bargeInPollIntervalMs: Long = 80,
        val bargeInConfirmGraceMs: Long = 500,
        val bargeInMinConfirmChars: Int = 2
    )

    private val _state = MutableStateFlow(ConversationState.IDLE)
    val state: StateFlow<ConversationState> = _state

    private var currentState: ConversationState
        get() = _state.value
        set(v) {
            if (_state.value != v) {
                Log.d(TAG, "State: ${_state.value} → $v")
                _state.value = v
                mainHandler.post { onStateChange(v) }
            }
        }

    private var vadEngine: LocalVadEngine? = null
    private var speechStartTime = 0L
    private var lastSpeechTime = 0L
    private var utteranceStartTime = 0L
    private var consecutiveShortPauses = 0

    @Volatile private var lastPartialText = ""
    @Volatile private var lastPartialTime = 0L
    @Volatile private var partialUnchangedMs = 0L
    private val hasReceivedFinal = AtomicBoolean(false)
    private val hasReceivedAnySpeech = AtomicBoolean(false)

    private val isBargeInCooldown = AtomicBoolean(false)
    private val userSpokeDuringTts = AtomicBoolean(false)
    private val bargeInCandidateActive = AtomicBoolean(false)
    private val bargeInTranscriptConfirmed = AtomicBoolean(false)
    @Volatile private var bargeInCandidateGeneration = 0

    private var endpointJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var audioManager: AudioManager? = null
    private var previousAudioMode = AudioManager.MODE_NORMAL

    fun attachVad(vad: LocalVadEngine) {
        this.vadEngine = vad
    }

    // ═══════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═══════════════════════════════════════════════════════════

    fun startListening() {
        Log.d(TAG, "startListening()")
        resetState()
        enableCommunicationMode(true)
        currentState = ConversationState.LISTENING
        utteranceStartTime = System.currentTimeMillis()
        hasReceivedFinal.set(false)
        hasReceivedAnySpeech.set(false)
        startEndpointLoop()
    }

    fun onPartialTranscript(text: String) {
        if (text.isBlank()) return
        hasReceivedAnySpeech.set(true)

        // ── تأكيد الـ barge-in: لو فيه فحص شغال دلوقتي ووصل partial حقيقي، نعتبره تأكيد
        if (bargeInCandidateActive.get() && text.trim().length >= config.bargeInMinConfirmChars) {
            bargeInTranscriptConfirmed.set(true)
        }

        val now = System.currentTimeMillis()
        if (text == lastPartialText) {
            partialUnchangedMs = now - lastPartialTime
        } else {
            lastPartialText = text
            lastPartialTime = now
            partialUnchangedMs = 0
            mainHandler.post { onPartialTranscript(text) }
        }
    }

    fun onFinalTranscript(text: String) {
        if (text.isBlank()) return
        Log.d(TAG, "onFinal: $text")
        hasReceivedFinal.set(true)
        lastPartialText = text
        mainHandler.post { onPartialTranscript(text) }
        finalizeUtterance(text)
    }

    fun notifyThinkingStarted() {
        Log.d(TAG, "notifyThinkingStarted()")
        currentState = ConversationState.THINKING
    }

    fun notifySpeakingStarted() {
        Log.d(TAG, "notifySpeakingStarted()")
        currentState = ConversationState.SPEAKING
        userSpokeDuringTts.set(false)
        isBargeInCooldown.set(true)
        bargeInCandidateGeneration++          // يلغي أي فحص barge-in قديم متأخر
        bargeInCandidateActive.set(false)
        bargeInTranscriptConfirmed.set(false)
        scope.launch {
            delay(config.bargeInCooldownMs)
            isBargeInCooldown.set(false)
        }
    }

    fun notifySpeakingEnded() {
        Log.d(TAG, "notifySpeakingEnded()")
        if (currentState == ConversationState.SPEAKING) {
            // بدل ما يرجع IDLE، نرجع LISTENING عشان نكمل المحادثة بدون Wake Word
            resetState()
            currentState = ConversationState.LISTENING
            utteranceStartTime = System.currentTimeMillis()
            hasReceivedFinal.set(false)
            hasReceivedAnySpeech.set(false)
            startEndpointLoop()
        }
    }

    fun onVadSpeechStart() {
        val now = System.currentTimeMillis()
        speechStartTime = now
        lastSpeechTime = now

        if (currentState == ConversationState.SPEAKING) {
            if (isBargeInCooldown.get()) return
            if (bargeInCandidateActive.get()) return // فيه فحص شغال بالفعل

            bargeInCandidateActive.set(true)
            bargeInTranscriptConfirmed.set(false)
            val myGen = ++bargeInCandidateGeneration

            scope.launch {
                // 1) استمرارية: الـ VAD لازم يفضل active طول فترة الـ threshold،
                //    مش لحظة واحدة بس — صدى الـ TTS غالباً بيبقى متقطع
                var elapsed = 0L
                var continuous = true
                while (elapsed < config.bargeInThresholdMs) {
                    delay(config.bargeInPollIntervalMs)
                    elapsed += config.bargeInPollIntervalMs
                    if (myGen != bargeInCandidateGeneration) { bargeInCandidateActive.set(false); return@launch }
                    if (vadEngine?.isCurrentlyActive != true) { continuous = false; break }
                }

                if (myGen != bargeInCandidateGeneration || currentState != ConversationState.SPEAKING) {
                    bargeInCandidateActive.set(false)
                    return@launch
                }
                if (!continuous) {
                    Log.d(TAG, "Barge-in candidate rejected — VAD not continuous (likely echo)")
                    bargeInCandidateActive.set(false)
                    return@launch
                }

                // 2) تأكيد بالـ transcript: نستنى partial نص حقيقي واصل من الـ STT
                //    صدى الـ TTS نادراً ما بيولّد transcript متّسق زي كلام حقيقي
                var waited = 0L
                while (waited < config.bargeInConfirmGraceMs && !bargeInTranscriptConfirmed.get()) {
                    delay(config.bargeInPollIntervalMs)
                    waited += config.bargeInPollIntervalMs
                    if (myGen != bargeInCandidateGeneration || currentState != ConversationState.SPEAKING) {
                        bargeInCandidateActive.set(false)
                        return@launch
                    }
                }

                bargeInCandidateActive.set(false)

                if (bargeInTranscriptConfirmed.get() && currentState == ConversationState.SPEAKING) {
                    Log.d(TAG, "🛑 BARGE-IN confirmed (continuous VAD + real transcript) — stopping TTS")
                    userSpokeDuringTts.set(true)
                    currentState = ConversationState.BARGE_IN
                    mainHandler.post { onBargeIn() }
                } else {
                    Log.d(TAG, "Barge-in candidate rejected — no transcript confirmation (likely echo)")
                }
            }
        }
    }

    fun onVadSpeechEnd() {
        val now = System.currentTimeMillis()
        val speechDuration = now - speechStartTime
        if (currentState == ConversationState.BARGE_IN) return
        if (speechDuration < config.minSpeechDurationMs) return
        consecutiveShortPauses = 0
    }

    fun stop() {
        Log.d(TAG, "stop()")
        endpointJob?.cancel()
        endpointJob = null
        resetState()
        currentState = ConversationState.IDLE
        enableCommunicationMode(false)
    }

    fun release() {
        stop()
        scope.cancel()
    }

    // ═══════════════════════════════════════════════════════════
    //  INTERNALS
    // ═══════════════════════════════════════════════════════════

    private fun startEndpointLoop() {
        endpointJob?.cancel()
        endpointJob = scope.launch {
            while (isActive && currentState == ConversationState.LISTENING) {
                val now = System.currentTimeMillis()

                // ← FIX: طول ما الـ VAD لسه حاسس بكلام فعلي دلوقتي، حدّث lastSpeechTime
                // باستمرار. من غير السطر ده، lastSpeechTime بتفضل واقفة على أول لحظة
                // بدأت الكلام فيها، فـ silenceMs بتكبر غلط حتى وإنت لسه بتتكلم.
                if (vadEngine?.isCurrentlyActive == true) {
                    lastSpeechTime = now
                }

                val silenceMs = now - lastSpeechTime
                val utteranceMs = now - utteranceStartTime

                if (utteranceMs > config.maxUtteranceMs) {
                    finalizeUtterance(lastPartialText); break
                }
                if (hasReceivedAnySpeech.get() && silenceMs > config.pauseLongMs) {
                    finalizeUtterance(lastPartialText); break
                }
                if (hasReceivedAnySpeech.get() && silenceMs > config.pauseMediumMs) {
                    if (partialUnchangedMs > config.pauseMediumMs) {
                        finalizeUtterance(lastPartialText); break
                    }
                }
                if (config.adaptiveEnabled && silenceMs in (config.pauseShortMs)..(config.pauseMediumMs)) {
                    if (partialUnchangedMs > config.pauseShortMs) {
                        consecutiveShortPauses++
                        if (consecutiveShortPauses >= 3) {
                            finalizeUtterance(lastPartialText); break
                        }
                    }
                }
                delay(50)
            }
        }
    }

    private fun finalizeUtterance(text: String) {
        if (currentState != ConversationState.LISTENING && currentState != ConversationState.BARGE_IN) return
        endpointJob?.cancel()
        endpointJob = null

        val finalText = text.trim()
        if (finalText.isBlank()) {
            currentState = ConversationState.IDLE
            enableCommunicationMode(false)
            return
        }
        currentState = ConversationState.THINKING
        mainHandler.post { onUserUtterance(finalText) }
    }

    private fun resetState() {
        lastPartialText = ""
        lastPartialTime = 0
        partialUnchangedMs = 0
        consecutiveShortPauses = 0
        hasReceivedFinal.set(false)
        hasReceivedAnySpeech.set(false)
        userSpokeDuringTts.set(false)
        isBargeInCooldown.set(false)
        bargeInCandidateActive.set(false)
        bargeInTranscriptConfirmed.set(false)
        bargeInCandidateGeneration++
    }

    private fun enableCommunicationMode(enable: Boolean) {
        if (audioManager == null) {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }
        if (enable) {
            if (audioManager!!.mode != AudioManager.MODE_IN_COMMUNICATION) {
                previousAudioMode = audioManager!!.mode
                audioManager!!.mode = AudioManager.MODE_IN_COMMUNICATION
            }
        } else {
            if (audioManager!!.mode == AudioManager.MODE_IN_COMMUNICATION) {
                audioManager!!.mode = previousAudioMode
            }
        }
    }

    fun applyEchoCancellation(audioSessionId: Int) {
        if (!config.echoCancellationEnabled) return
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                val aec = AcousticEchoCanceler.create(audioSessionId)
                aec?.enabled = true
            }
            if (NoiseSuppressor.isAvailable()) {
                val ns = NoiseSuppressor.create(audioSessionId)
                ns?.enabled = true
            }
        } catch (e: Exception) {}
    }
}