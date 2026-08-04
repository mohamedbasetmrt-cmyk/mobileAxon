package com.example.app_abdelbaset

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor

class DeepgramSttEngine(
    private val apiKey: String,
    private val language: String = "en",
    private val model: String = "nova-3",
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "DeepgramSttEngine"
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_BYTES = 640
        private const val MAX_RECONNECTS = 3
        private const val RECONNECT_DELAY_MS = 1000L
    }

    var onAudioFrame: ((ShortArray, Int) -> Unit)? = null

    val isStreaming: Boolean get() = isRunning.get()

    private val isRunning = AtomicBoolean(false)
    private val wsReady   = AtomicBoolean(false)
    private val isClosed  = AtomicBoolean(false)

    @Volatile private var reconnectAttempts = 0

    private var webSocket:     WebSocket?   = null
    private var audioRecord:   AudioRecord? = null
    private var captureThread: Thread?      = null

    @Volatile private var latestPartialText = ""
    @Volatile private var finalTextReceived = ""

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout on a long-lived stream
            .pingInterval(20, TimeUnit.SECONDS)     // more tolerant of brief network hiccups
            .build()
    }

    fun start() {
        if (isRunning.getAndSet(true)) return
        isClosed.set(false)
        finalTextReceived = ""
        latestPartialText = ""
        Log.i(TAG, "start() -> opening Deepgram WS")
        openWebSocket()
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        Log.i(TAG, "stop() -> sending CloseStream to Deepgram")
        try {
            webSocket?.send("""{"type":"CloseStream"}""")
        } catch (e: Exception) {
            Log.w(TAG, "CloseStream send failed: ${e.message}")
        }
        captureThread?.interrupt()
        stopAudioRecord()
    }

    fun releaseStream() {
        webSocket?.close(1000, "Done")
        webSocket = null
        wsReady.set(false)
        Log.d(TAG, "releaseStream()")
    }

    fun getFinalText(): String {
        return finalTextReceived.ifBlank { latestPartialText }
    }

    fun reset() {
        latestPartialText = ""
        finalTextReceived = ""
        isClosed.set(false)
        Log.d(TAG, "reset()")
    }

    fun release() {
        stop()
        releaseStream()
        httpClient.dispatcher.executorService.shutdown()
        Log.i(TAG, "released")
    }

    private fun openWebSocket() {
        val url = buildString {
            append("wss://api.deepgram.com/v1/listen")
            append("?model=$model")
            append("&language=$language")
            append("&encoding=linear16")
            append("&sample_rate=$SAMPLE_RATE")
            append("&channels=1")
            append("&interim_results=true")
            append("&smart_format=true")
            append("&endpointing=false")
            append("&utterance_end_ms=1000")
        }

        Log.d(TAG, "WS -> $url")

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Token $apiKey")
            .build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "Deepgram WS open")
                reconnectAttempts = 0
                wsReady.set(true)
                if (captureThread == null || captureThread?.isAlive != true) {
                    startCaptureThread()
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleDeepgramMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WS failure: ${t.message}  code=${response?.code}")
                wsReady.set(false)
                if (isClosed.get() || !isRunning.get()) return

                if (reconnectAttempts < MAX_RECONNECTS) {
                    reconnectAttempts++
                    Log.w(TAG, "Attempting Deepgram reconnect $reconnectAttempts/$MAX_RECONNECTS")
                    Thread {
                        try {
                            Thread.sleep(RECONNECT_DELAY_MS * reconnectAttempts)
                        } catch (_: InterruptedException) {}
                        if (isRunning.get() && !isClosed.get()) openWebSocket()
                    }.start()
                } else {
                    isRunning.set(false)
                    onError("Deepgram connection failed: ${t.message}")
                    stopAudioRecord()
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WS closed: $code $reason")
                wsReady.set(false)
            }
        })
    }

    private fun handleDeepgramMessage(raw: String) {
        try {
            val json = JSONObject(raw)
            val type = json.optString("type")

            when (type) {
                "Results" -> {
                    val channel    = json.optJSONObject("channel") ?: return
                    val alts       = channel.optJSONArray("alternatives") ?: return
                    val transcript = alts.getJSONObject(0).optString("transcript", "").trim()
                    val isFinal    = json.optBoolean("is_final", false)
                    val speechFinal= json.optBoolean("speech_final", false)

                    if (transcript.isBlank()) return

                    when {
                        speechFinal -> {
                            Log.i(TAG, "speech_final: $transcript")
                            finalTextReceived = transcript
                            onFinal(transcript)
                        }
                        isFinal -> {
                            Log.d(TAG, "is_final: $transcript")
                            latestPartialText = transcript
                            onPartial(transcript)
                        }
                        else -> {
                            Log.v(TAG, "interim: $transcript")
                            latestPartialText = transcript
                            onPartial(transcript)
                        }
                    }
                }

                "Metadata" -> {
                    Log.d(TAG, "Deepgram metadata: $raw")
                }

                "SpeechStarted" -> {
                    Log.d(TAG, "Deepgram: SpeechStarted")
                }

                "UtteranceEnd" -> {
                    Log.d(TAG, "Deepgram: UtteranceEnd")
                    if (finalTextReceived.isBlank() && latestPartialText.isNotBlank()) {
                        finalTextReceived = latestPartialText
                        onFinal(latestPartialText)
                    }
                }

                "Close" -> {
                    Log.d(TAG, "Deepgram: stream closed by server")
                }

                "Error" -> {
                    val err = json.optString("message", "Unknown Deepgram error")
                    Log.e(TAG, "Deepgram error: $err")
                    onError("Deepgram: $err")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Message parse failed: ${e.message}")
        }
    }

    // استبدل دالة startCaptureThread في DeepgramSttEngine.kt بالكود ده:

    private fun startCaptureThread() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val recBuf = maxOf(minBuf, CHUNK_BYTES * 8)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, // ← التعديل هنا
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, recBuf
        ).also { ar ->
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                onError("AudioRecord init failed")
                return
            }

            // تفعيل الـ Echo Cancellation
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    val aec = AcousticEchoCanceler.create(ar.audioSessionId)
                    aec?.enabled = true
                }
                if (NoiseSuppressor.isAvailable()) {
                    val ns = NoiseSuppressor.create(ar.audioSessionId)
                    ns?.enabled = true
                }
            } catch (_: Exception) {}

            ar.startRecording()
            Log.d(TAG, "AudioRecord started with VOICE_COMMUNICATION & AEC")
        }

        captureThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val byteBuffer  = ByteArray(CHUNK_BYTES)
            val shortBuffer = ShortArray(CHUNK_BYTES / 2)

            try {
                while (!Thread.currentThread().isInterrupted && isRunning.get()) {
                    val ar = audioRecord ?: break
                    val read = ar.read(byteBuffer, 0, CHUNK_BYTES)
                    if (read <= 0) continue

                    for (i in shortBuffer.indices) {
                        val lo = byteBuffer[i * 2].toInt() and 0xFF
                        val hi = byteBuffer[i * 2 + 1].toInt()
                        shortBuffer[i] = ((hi shl 8) or lo).toShort()
                    }
                    onAudioFrame?.invoke(shortBuffer, shortBuffer.size)

                    if (wsReady.get()) {
                        webSocket?.send(byteBuffer.copyOf(read).toByteString())
                    }
                }
            } catch (e: InterruptedException) {
                Log.d(TAG, "Capture thread interrupted")
            } catch (e: Exception) {
                Log.e(TAG, "Capture error: ${e.message}")
            } finally {
                stopAudioRecord()
            }
        }, "deepgram-capture")

        captureThread?.isDaemon = true
        captureThread?.start()
    }

    private fun stopAudioRecord() {
        try {
            audioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
                release()
            }
            audioRecord = null
            Log.d(TAG, "AudioRecord released")
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord release error: ${e.message}")
        }
    }
}