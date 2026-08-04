package com.example.app_abdelbaset

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AxonVoiceSession — v4
 *
 * NEW: Sends clearText broadcast when speaking ends (transitionToIdle).
 */
class AxonVoiceSession(
    private val context:             Context,
    serverWsBaseUrl: String,
    serverHttpBaseUrl: String,
    private val onStateChanged:      (State) -> Unit,
    private val onPartialTranscript: (String) -> Unit = {},
    private val onFinalTranscript:   (String) -> Unit = {},
    private val onLlmToken:          (String) -> Unit = {},
    private val onLlmResponse:       (String) -> Unit = {},
    private val onProgress:          (String) -> Unit = {},
    private val onError:             (String) -> Unit = {},
    private val useDirectDeepgram:   Boolean = false,
    private val deepgramApiKey:      String  = ""
) {

    enum class State {
        IDLE,
        STREAMING_STT,
        WAITING_TRANSCRIPT,
        LLM_THINKING,
        TTS_PLAYING,
        ERROR
    }

    companion object {
        private const val TAG = "AxonSession"
    }

    private val visualizer = VisualizerState.instance

    private var currentState = State.IDLE
        set(value) {
            if (field != value) {
                field = value
                Log.d(TAG, "State -> $value")
                updateVisualizerForState(value)
                mainScope.launch { onStateChanged(value) }
            }
        }

    private val sessionId  = UUID.randomUUID().toString().take(8)
    private val mainScope  = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val isReleased = AtomicBoolean(false)

    private var audioStreamer: AudioStreamingManager? = null
    private var ttsPlayer:     TtsStreamingPlayer?   = null
    private var llmClient:     LlmStreamingClient?   = null
    private val mobileActionExecutor = MobileActionExecutor(context)
    private val serverWsBaseUrl   = serverWsBaseUrl.trimEnd('/')
    private val serverHttpBaseUrl = serverHttpBaseUrl.trimEnd('/')
    private val llmTokenBuffer = StringBuilder()

    private var sttPulseJob: Job? = null

    @Volatile private var ttsPlayerReady   = false
    private val pendingSentences           = mutableListOf<String>()
    private var queueDonePending           = false

    init {
        Log.d(TAG, "Session created  id=$sessionId")
        initLlmClient()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  NEW: Orb helpers
    // ═══════════════════════════════════════════════════════════════════

    private fun sendTextToOrb(text: String) {
        val intent = android.content.Intent("com.example.app_abdelbaset.ORB_TEXT")
        intent.putExtra("text", text)
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }

    private fun clearOrbText() {
        val intent = android.content.Intent("com.example.app_abdelbaset.ORB_TEXT")
        intent.putExtra("text", "")  // Empty = clear
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  VISUALIZER STATE SYNC
    // ═══════════════════════════════════════════════════════════════════

    private fun updateVisualizerForState(state: State) {
        visualizer.setAudioLevel(0f)
        sttPulseJob?.cancel()
        sttPulseJob = null

        when (state) {
            State.IDLE -> {
                visualizer.setSpeaking(false)
                visualizer.setListening(false)
                visualizer.deactivate()
            }
            State.STREAMING_STT -> {
                visualizer.activate()
                visualizer.setListening(true)
                visualizer.setSpeaking(false)
                sttPulseJob = mainScope.launch {
                    while (isActive) {
                        visualizer.setAudioLevel(0.35f)
                        delay(120)
                        visualizer.setAudioLevel(0.15f)
                        delay(120)
                    }
                }
            }
            State.WAITING_TRANSCRIPT -> {
                visualizer.setState(VisualizerState.OrbState.THINKING)
                visualizer.setAudioLevel(0.05f)
            }
            State.LLM_THINKING -> {
                visualizer.setState(VisualizerState.OrbState.THINKING)
                visualizer.setAudioLevel(0f)
            }
            State.TTS_PLAYING -> {
                visualizer.setSpeaking(true)
            }
            State.ERROR -> {
                visualizer.setSpeaking(false)
                visualizer.setListening(false)
                visualizer.deactivate()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  LLM CLIENT
    // ═══════════════════════════════════════════════════════════════════

    private fun initLlmClient() {
        llmClient = LlmStreamingClient(
            serverBaseUrl = serverWsBaseUrl,
            sessionId     = sessionId,

            onToken = { token ->
                llmTokenBuffer.append(token)
                onLlmToken(token)
            },

            onSentence = { sentence ->
                Log.d(TAG, "LLM sentence: ${sentence.take(60)}")
                sendTextToOrb(sentence)  // ← NEW: Stream to orb
                when (currentState) {
                    State.LLM_THINKING -> {
                        Log.d(TAG, "First sentence -> starting TTS early")
                        startTts(sentence)
                    }
                    State.TTS_PLAYING -> {
                        if (ttsPlayerReady) {
                            Log.d(TAG, "Queuing sentence for TTS")
                            ttsPlayer?.queueChunk(sentence)
                        } else {
                            Log.d(TAG, "AudioTrack not ready — buffering")
                            pendingSentences.add(sentence)
                        }
                    }
                    else -> Log.w(TAG, "Sentence in unexpected state $currentState")
                }
            },

            onProgress = { msg ->
                Log.d(TAG, "LLM progress: $msg")
                onProgress(msg)
            },
            onAction      = { actionJson ->
                mobileActionExecutor.execute(actionJson)
            },

            onDone = { fullResponse ->
                Log.d(TAG, "LLM done")
                sendTextToOrb(fullResponse)  // ← NEW: Final response
                onLlmResponse(fullResponse)

                when {
                    currentState == State.TTS_PLAYING -> {
                        if (ttsPlayerReady) {
                            Log.d(TAG, "LLM done -> markQueueDone()")
                            ttsPlayer?.markQueueDone()
                        } else {
                            Log.d(TAG, "LLM done -> deferring markQueueDone()")
                            queueDonePending = true
                        }
                    }
                    fullResponse.isNotBlank() -> {
                        Log.d(TAG, "No sentence before done — starting TTS")
                        startTts(fullResponse)
                        queueDonePending = true
                    }
                    else -> {
                        Log.w(TAG, "Empty LLM response -> idle")
                        transitionToIdle()
                    }
                }
            },

            onError = { err ->
                Log.e(TAG, "LLM error: $err")
                onError("LLM: $err")
                transitionToIdle()
            }
        ).also { it.connect() }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ═══════════════════════════════════════════════════════════════════

    fun onWakeWordDetected() {
        if (currentState != State.IDLE) {
            Log.w(TAG, "Wake word ignored – not IDLE")
            return
        }
        Log.d(TAG, "Wake word detected -> starting STT")
        startSttStreaming()
    }

    fun cancel() {
        Log.d(TAG, "Manual cancel")
        sttPulseJob?.cancel()
        audioStreamer?.stopStreaming()
        ttsPlayer?.stopPlayback()
        transitionToIdle()
    }

    fun sendDirectCommand(text: String) {
        if (currentState != State.IDLE) {
            Log.w(TAG, "sendDirectCommand ignored – session busy")
            return
        }
        Log.d(TAG, "Direct command: $text")
        onFinalTranscript(text)
        startLlm(text)
    }

    fun clearHistory() {
        llmClient?.disconnect()
        llmClient?.release()
        llmClient = null
        Log.d(TAG, "History cleared – reconnecting LLM")
        initLlmClient()
    }

    fun release() {
        if (isReleased.getAndSet(true)) return
        Log.d(TAG, "Releasing session $sessionId")
        sttPulseJob?.cancel()
        visualizer.deactivate()
        audioStreamer?.release()
        ttsPlayer?.release()
        llmClient?.release()
        mainScope.cancel()
    }

    val state: State get() = currentState

    // ═══════════════════════════════════════════════════════════════════
    //  STEP 1 – STT
    // ═══════════════════════════════════════════════════════════════════

    private fun startSttStreaming() {
        currentState = State.STREAMING_STT

        audioStreamer?.release()
        audioStreamer = AudioStreamingManager(
            context             = context,
            serverBaseUrl       = serverWsBaseUrl,
            sessionId           = sessionId,
            onPartialTranscript = { text ->
                Log.d(TAG, "Partial: $text")
                visualizer.setAudioLevel(0.5f)
                onPartialTranscript(text)
            },
            onFinalTranscript   = { text ->
                Log.d(TAG, "Final STT: $text")
                sttPulseJob?.cancel()
                visualizer.setAudioLevel(0f)
                onFinalTranscript(text)
                if (text.isNotBlank()) {
                    currentState = State.WAITING_TRANSCRIPT
                    startLlm(text)
                } else {
                    Log.w(TAG, "Empty transcript -> idle")
                    transitionToIdle()
                }
            },
            onError             = { err ->
                Log.e(TAG, "STT error: $err")
                onError("STT: $err")
                transitionToIdle()
            },
            onStreamingStarted  = { Log.d(TAG, "STT streaming started") },
            onStreamingStopped  = {
                Log.d(TAG, "STT streaming stopped")
                if (currentState == State.STREAMING_STT) {
                    onError("No transcript received")
                    transitionToIdle()
                }
            },
            useDirectDeepgram   = useDirectDeepgram,
            deepgramApiKey      = deepgramApiKey
        )
        audioStreamer?.startStreaming()
    }

    // ═══════════════════════════════════════════════════════════════════
    //  STEP 2 – LLM
    // ═══════════════════════════════════════════════════════════════════

    private fun startLlm(userText: String) {
        currentState = State.LLM_THINKING
        llmTokenBuffer.clear()

        val client = llmClient ?: run { initLlmClient(); llmClient!! }
        client.sendMessage(userText)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  STEP 3 – TTS
    // ═══════════════════════════════════════════════════════════════════

    private fun startTts(text: String) {
        currentState    = State.TTS_PLAYING
        ttsPlayerReady  = false
        pendingSentences.clear()
        queueDonePending = false

        ttsPlayer?.release()
        ttsPlayer = TtsStreamingPlayer(
            serverBaseUrl      = serverHttpBaseUrl,
            onAudioLevel       = { level ->
                visualizer.setAudioLevel(level)
            },
            onPlaybackStarted  = {
                Log.d(TAG, "TTS AudioTrack ready")
                ttsPlayerReady = true
                pendingSentences.forEach { ttsPlayer?.queueChunk(it) }
                pendingSentences.clear()
                if (queueDonePending) {
                    Log.d(TAG, "Flushing deferred markQueueDone()")
                    ttsPlayer?.markQueueDone()
                    queueDonePending = false
                }
            },
            onPlaybackFinished = {
                Log.d(TAG, "TTS finished -> idle")
                ttsPlayerReady = false
                transitionToIdle()
            },
            onError            = { err ->
                Log.e(TAG, "TTS error: $err")
                ttsPlayerReady = false
                transitionToIdle()
            }
        )
        ttsPlayer?.speak(text)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  NEW: transitionToIdle with clearOrbText
    // ═══════════════════════════════════════════════════════════════════

    private fun transitionToIdle() {
        sttPulseJob?.cancel()
        sttPulseJob = null
        ttsPlayerReady = false
        pendingSentences.clear()
        queueDonePending = false
        audioStreamer?.release()
        audioStreamer = null

        // ← NEW: Clear orb text when speaking ends
        clearOrbText()

        currentState = State.IDLE
    }
}