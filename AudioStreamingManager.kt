package com.example.app_abdelbaset

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * AudioStreamingManager — v6 (PREWARM EDITION)
 * ═══════════════════════════════════════════════════════════════════════
 *
 * NEW in v6: prewarm() function
 * ─────────────────────────────
 * Call prewarm() BEFORE wake word is detected (e.g. right when the app
 * starts listening for wake word). This opens the WebSocket connection
 * to /ws/stt early so that when the wake word fires, the WS is already
 * open and we can start sending audio IMMEDIATELY.
 *
 * Flow WITHOUT prewarm (old):
 *   wake word → stop wake word detector → wait 2000ms → open WS → wait ~1s → send audio
 *   Total delay: ~3 seconds
 *
 * Flow WITH prewarm (new):
 *   app start → prewarm() → WS opens in background
 *   wake word → stop wake word detector → wait 500ms → AudioRecord open → send audio
 *   WS was already open → zero WS connect time
 *   Total delay: ~500ms
 *
 * ─────────────────────────────────────────────────────────────────────
 * All v5 fixes preserved:
 *  Fix 1 — AudioSource.MIC (not VOICE_RECOGNITION) → ColorOS safe
 *  Fix 2 — Audio focus + MODE_IN_COMMUNICATION
 *  Fix 3 — THREAD_PRIORITY_URGENT_AUDIO capture thread
 *  Fix 4 — WS sends off the audio thread
 * ═══════════════════════════════════════════════════════════════════════
 */
class AudioStreamingManager(
    private val context: Context,
    private val serverBaseUrl: String,
    private val sessionId: String,
    private val onPartialTranscript: (String) -> Unit,
    private val onFinalTranscript:   (String) -> Unit,
    private val onError:             (String) -> Unit,
    private val onStreamingStarted:  () -> Unit,
    private val onStreamingStopped:  () -> Unit,
    private val useDirectDeepgram:   Boolean = false,   // NEW
    private val deepgramApiKey:      String  = ""       // NEW
) {

    companion object {
        private const val TAG = "AudioStreaming"

        const val SAMPLE_RATE    = 16_000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT   = AudioFormat.ENCODING_PCM_16BIT

        // 20 ms of audio = 640 bytes at 16kHz 16-bit mono
        private const val CHUNK_BYTES = 640

        private const val WS_CONNECT_TIMEOUT_MS = 10_000L

        // Set true ONLY to diagnose TLS/cert issues. NEVER in production.
        private const val DEBUG_TRUST_ALL_CERTS = false
    }

    private val isStreaming   = AtomicBoolean(false)
    private val isStopped     = AtomicBoolean(false)
    private val wsReady       = AtomicBoolean(false)
    // NEW: tracks whether prewarm has already opened the WS
    private val isPrewarmed   = AtomicBoolean(false)

    private var audioRecord:    AudioRecord?  = null
    private var captureThread:  Thread?       = null
    private var webSocket:      WebSocket?    = null
    private var wsOpenDeferred: CompletableDeferred<Boolean>? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var audioFocusGranted = false

    private val httpClient: OkHttpClient by lazy { buildHttpClient() }

    private fun buildHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .pingInterval(10, TimeUnit.SECONDS)

        if (DEBUG_TRUST_ALL_CERTS) {
            Log.w(TAG, "⚠️ DEBUG_TRUST_ALL_CERTS=true — DISABLE IN PRODUCTION!")
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
                override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val ssl = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustAll), java.security.SecureRandom())
            }
            builder
                .sslSocketFactory(ssl.socketFactory, trustAll)
                .hostnameVerifier { hostname, session ->
                    Log.d(TAG, "TLS verify (trust-all): $hostname  cipher=${session.cipherSuite}")
                    true
                }
        }

        builder.addInterceptor(
            HttpLoggingInterceptor { msg -> Log.d("OkHttp/STT", msg) }
                .apply { level = HttpLoggingInterceptor.Level.HEADERS }
        )
        return builder.build()
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * PREWARM: Open the WebSocket connection EARLY (before wake word fires).
     *
     * Call this right after wake word detection starts (i.e. inside
     * startWakeWordDetection() in MicForegroundService). By the time the
     * user says "Axon" and the mic is released (~500ms), the WS will
     * already be open and waiting.
     *
     * Safe to call multiple times — ignored if already prewarmed.
     */
    fun prewarm() {
        if (isPrewarmed.getAndSet(true)) {
            Log.d(TAG, "prewarm() already done — skipping")
            return
        }
        Log.d(TAG, "prewarm() → opening WebSocket early…")
        openWebSocket()
    }

    fun startStreaming() {
        if (isStreaming.getAndSet(true)) {
            Log.w(TAG, "Already streaming — ignoring")
            return
        }
        isStopped.set(false)

        scope.launch {
            try {
                // ── Step 0: AudioRecord pre-check ────────────────────────
                Log.d(TAG, "Step 0: Pre-checking AudioRecord…")
                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "getMinBufferSize()=$minBuf — mic busy (previous detector not released?)")
                    withContext(Dispatchers.Main) {
                        onError("Microphone busy — try again in a moment")
                    }
                    isStreaming.set(false)
                    return@launch
                }
                Log.d(TAG, "AudioRecord minBufferSize=$minBuf ✅")

                // ── Step 1: Request audio focus ──────────────────────────
                requestAudioFocus()

                // ── Step 2: Open WebSocket (or reuse prewarmed one) ───────
                val deferred: CompletableDeferred<Boolean>

                if (wsReady.get()) {
                    // ✅ PREWARM SUCCESS: WS already open — skip connect wait!
                    Log.d(TAG, "Step 2: WebSocket already open from prewarm ✅ — zero wait!")
                    // Step 4 directly
                    Log.d(TAG, "Step 4: Starting high-priority audio capture immediately")
                    startCaptureThread(minBuf)
                    withContext(Dispatchers.Main) { onStreamingStarted() }
                    return@launch
                }

                // WS not open yet (prewarm still connecting, or prewarm not called)
                if (isPrewarmed.get() && wsOpenDeferred != null) {
                    // Prewarm was called but WS not open yet — wait for it
                    Log.d(TAG, "Step 2: Prewarm in progress — waiting for WS open…")
                    deferred = wsOpenDeferred!!
                } else {
                    // No prewarm — open WS now (fallback)
                    Log.d(TAG, "Step 2: No prewarm — opening WebSocket now…")
                    deferred = openWebSocket()
                }

                // ── Step 3: Wait for WS open ──────────────────────────────
                Log.d(TAG, "Step 3: Waiting up to ${WS_CONNECT_TIMEOUT_MS}ms for WS open…")
                val connected = withTimeoutOrNull(WS_CONNECT_TIMEOUT_MS) {
                    deferred.await()
                } ?: false

                if (!connected) {
                    Log.e(TAG, "WS did not open in time — URL: $serverBaseUrl/ws/stt/$sessionId")
                    withContext(Dispatchers.Main) {
                        onError("Cannot reach server at $serverBaseUrl — check URL and network")
                    }
                    cleanup()
                    return@launch
                }

                // ── Step 4: Start high-priority capture thread ────────────
                Log.d(TAG, "Step 4: WS open ✅ — starting high-priority audio capture")
                startCaptureThread(minBuf)
                withContext(Dispatchers.Main) { onStreamingStarted() }

            } catch (e: Exception) {
                Log.e(TAG, "startStreaming error: ${e.javaClass.simpleName}: ${e.message}", e)
                withContext(Dispatchers.Main) { onError("Stream start failed: ${e.message}") }
                cleanup()
            }
        }
    }

    /** Manual cancel — only needed for a UI cancel button. */
    fun stopStreaming() {
        if (!isStreaming.get()) return
        Log.d(TAG, "Manual stopStreaming()")
        sendStopToServer()
    }

    fun release() {
        cleanup()
        scope.cancel()
        httpClient.dispatcher.executorService.shutdown()
    }

    val isCurrentlyStreaming: Boolean get() = isStreaming.get()

    // ─────────────────────────────────────────────────────────────────────
    // WEBSOCKET OPEN (extracted so prewarm and startStreaming can share it)
    // ─────────────────────────────────────────────────────────────────────

    private fun openWebSocket(): CompletableDeferred<Boolean> {
        val deferred = CompletableDeferred<Boolean>()
        wsOpenDeferred = deferred
        wsReady.set(false)

        val request = if (useDirectDeepgram) {
            val url = "wss://api.deepgram.com/v2/listen" +
                    "?model=flux-general-en" +
                    "&encoding=linear16" +
                    "&sample_rate=16000"

            Log.d(TAG, "Opening WebSocket → Deepgram direct: $url")
            Request.Builder()
                .url(url)
                .addHeader("Authorization", "Token $deepgramApiKey")
                .build()
        } else {
            val url = "$serverBaseUrl/ws/stt/$sessionId"
            Log.d(TAG, "Opening WebSocket → $url")
            Request.Builder().url(url).build()
        }

        webSocket = httpClient.newWebSocket(request, createWsListener())
        return deferred
    }

    // ─────────────────────────────────────────────────────────────────────
    // AUDIO FOCUS
    // ─────────────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun requestAudioFocus() {
        previousAudioMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        val result = audioManager.requestAudioFocus(
            null,
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        )
        audioFocusGranted = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        Log.d(TAG, "AudioFocus: ${if (audioFocusGranted) "GRANTED ✅" else "DENIED ⚠️ (continuing anyway)"}")
        Log.d(TAG, "AudioManager mode: $previousAudioMode → ${audioManager.mode}")
    }

    @Suppress("DEPRECATION")
    private fun releaseAudioFocus() {
        if (audioFocusGranted) {
            audioManager.abandonAudioFocus(null)
            audioFocusGranted = false
        }
        audioManager.mode = previousAudioMode
        Log.d(TAG, "AudioFocus released, mode restored to $previousAudioMode")
    }

    // ─────────────────────────────────────────────────────────────────────
    // WEBSOCKET LISTENER
    // ─────────────────────────────────────────────────────────────────────

    private fun createWsListener() = object : WebSocketListener() {

        override fun onOpen(ws: WebSocket, response: Response) {
            Log.d(TAG, "STT WS OPEN ✅  code=${response.code}")
            wsReady.set(true)
            wsOpenDeferred?.complete(true)
        }

        override fun onMessage(ws: WebSocket, text: String) {
            try {
                if (useDirectDeepgram) {
                    // Deepgram native format
                    val json        = JSONObject(text)
                    val channel     = json.optJSONObject("channel") ?: return
                    val alts        = channel.optJSONArray("alternatives") ?: return
                    val transcript  = alts.getJSONObject(0).optString("transcript", "")
                    val isFinal     = json.optBoolean("is_final", false)
                    val speechFinal = json.optBoolean("speech_final", false)

                    if (transcript.isBlank()) return

                    if (speechFinal) {
                        Log.d(TAG, "Deepgram Final ✅: $transcript")
                        isStopped.set(true)
                        captureThread?.interrupt()
                        scope.launch(Dispatchers.Main) { onFinalTranscript(transcript) }
                        cleanup()
                    } else if (isFinal) {
                        Log.d(TAG, "Deepgram Partial: $transcript")
                        scope.launch(Dispatchers.Main) { onPartialTranscript(transcript) }
                    }
                } else {
                    // Server format
                    val json = JSONObject(text)
                    val type = json.optString("type")
                    Log.d(TAG, "STT ← type=$type")

                    when (type) {
                        "partial" -> {
                            val t = json.optString("text", "")
                            if (t.isNotBlank()) {
                                Log.d(TAG, "Partial: $t")
                                scope.launch(Dispatchers.Main) { onPartialTranscript(t) }
                            }
                        }
                        "final" -> {
                            val t = json.optString("text", "")
                            Log.d(TAG, "Final ✅: $t")
                            isStopped.set(true)
                            captureThread?.interrupt()
                            scope.launch(Dispatchers.Main) { onFinalTranscript(t) }
                            cleanup()
                        }
                        "error" -> {
                            val err = json.optString("text", "Unknown STT error")
                            Log.e(TAG, "Server STT error: $err")
                            scope.launch(Dispatchers.Main) { onError(err) }
                            cleanup()
                        }
                        else -> Log.d(TAG, "Unknown msg type '$type' | raw: $text")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "WS message parse error: ${e.message}")
            }
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "STT WS FAILURE: ${t.javaClass.simpleName}: ${t.message}")
            Log.e(TAG, "  response code=${response?.code}  msg=${response?.message}")
            when {
                t.message?.contains("CLEARTEXT", ignoreCase = true) == true ->
                    Log.e(TAG, "  → ws:// vs wss:// mismatch")
                t.message?.contains("certificate", ignoreCase = true) == true ||
                        t.message?.contains("handshake",   ignoreCase = true) == true ->
                    Log.e(TAG, "  → TLS error: set DEBUG_TRUST_ALL_CERTS=true to diagnose")
                t.message?.contains("Broken pipe", ignoreCase = true) == true ->
                    Log.e(TAG, "  → Broken pipe: mobile stopped sending audio")
            }
            wsReady.set(false)
            isPrewarmed.set(false)  // reset so next prewarm() works
            wsOpenDeferred?.complete(false)
            scope.launch(Dispatchers.Main) { onError("WebSocket failure: ${t.message}") }
            cleanup()
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "STT WS closed: code=$code  reason=$reason")
            wsReady.set(false)
            isPrewarmed.set(false)  // reset for next session
            if (isStreaming.get()) {
                scope.launch(Dispatchers.Main) { onStreamingStopped() }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // HIGH-PRIORITY CAPTURE THREAD
    // ─────────────────────────────────────────────────────────────────────

    private fun startCaptureThread(minBuf: Int) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) throw SecurityException("RECORD_AUDIO permission not granted")

        val recBuf = maxOf(minBuf, CHUNK_BYTES * 8)
        Log.d(TAG, "AudioRecord: sampleRate=$SAMPLE_RATE  recBuf=$recBuf  chunkBytes=$CHUNK_BYTES  source=MIC")

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, recBuf
        ).also { ar ->
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord init failed (state=${ar.state})")
            }
            ar.startRecording()
            Log.d(TAG, "AudioRecord recording ✅  recordingState=${ar.recordingState}")
        }

        captureThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            Log.d(TAG, "Capture thread priority set to URGENT_AUDIO ✅")

            val buf        = ByteArray(CHUNK_BYTES)
            var framesSent = 0
            var framesRead = 0

            Log.d(TAG, "Capture thread running — Deepgram will signal when speech ends")

            try {
                while (!Thread.currentThread().isInterrupted
                    && isStreaming.get()
                    && !isStopped.get()
                ) {
                    val ar = audioRecord
                    if (ar == null || ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        Log.e(TAG, "AudioRecord stopped unexpectedly! recordingState=${ar?.recordingState}")
                        break
                    }

                    val read = ar.read(buf, 0, buf.size)
                    if (read <= 0) {
                        Log.w(TAG, "AudioRecord.read()=$read  framesRead=$framesRead  (stall?)")
                        Thread.sleep(5)
                        continue
                    }
                    framesRead++

                    if (!wsReady.get()) continue

                    val frame = buf.copyOf(read)
                    scope.launch(Dispatchers.IO) {
                        val ok = webSocket?.send(frame.toByteString())
                        if (ok == false || ok == null) {
                            Log.e(TAG, "WS.send() failed — connection lost")
                            if (!isStopped.get()) {
                                isStopped.set(true)
                                isStreaming.set(false)
                                withContext(Dispatchers.Main) {
                                    onError("Connection lost while streaming audio")
                                }
                                cleanup()
                            }
                        }
                    }

                    framesSent++
                    if (framesSent == 1) Log.d(TAG, "First frame dispatched ✅")
                    if (framesSent % 200 == 0) {
                        Log.d(TAG, "Frames sent: $framesSent  reads: $framesRead  AR.state=${audioRecord?.recordingState}")
                    }
                }
            } catch (e: InterruptedException) {
                Log.d(TAG, "Capture thread interrupted (normal stop)")
            } catch (e: Exception) {
                Log.e(TAG, "Capture thread error: ${e.message}", e)
                scope.launch(Dispatchers.Main) { onError("Capture error: ${e.message}") }
            } finally {
                Log.d(TAG, "Capture thread ended — total frames: $framesSent  reads: $framesRead")
                stopAudioRecord()
                releaseAudioFocus()
            }
        }, "axon-capture")

        captureThread?.isDaemon = true
        captureThread?.start()
    }

    // ─────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private fun sendStopToServer() {
        if (isStopped.getAndSet(true)) return
        Log.d(TAG, "Sending manual stop to server")
        try { webSocket?.send("""{"type":"stop"}""") }
        catch (e: Exception) { Log.w(TAG, "Stop send failed: ${e.message}") }
        isStreaming.set(false)
        captureThread?.interrupt()
        stopAudioRecord()
        releaseAudioFocus()
    }

    private fun stopAudioRecord() {
        try {
            audioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
                release()
                Log.d(TAG, "AudioRecord released ✅")
            }
            audioRecord = null
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord release error: ${e.message}")
        }
    }

    private fun cleanup() {
        Log.d(TAG, "cleanup()")
        isStreaming.set(false)
        isStopped.set(true)
        wsReady.set(false)
        isPrewarmed.set(false)
        wsOpenDeferred?.cancel()
        wsOpenDeferred = null
        captureThread?.interrupt()
        captureThread = null
        stopAudioRecord()
        releaseAudioFocus()
        webSocket?.close(1000, "Done")
        webSocket = null
        Log.d(TAG, "AudioStreamingManager cleaned up ✅")
    }
}