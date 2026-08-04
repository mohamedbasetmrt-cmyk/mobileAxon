package com.example.app_abdelbaset

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

enum class VoiceState { IDLE, RECORDING, PROCESSING }

class VoiceInputManager(
    private val context: Context,
    private val endpoint: String,
    private val onTranscript: (String) -> Unit,   // لما يجي transcript يحطه في الـ input
    private val onStateChange: (VoiceState) -> Unit,
    private val onError: (String) -> Unit
) {
    private val SAMPLE_RATE  = 16000
    private val CHANNEL_CFG  = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE  = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CFG, AUDIO_FORMAT)
        .coerceAtLeast(4096)

    private var audioRecord: AudioRecord? = null
    private var sttWebSocket: WebSocket?  = null
    private var recordingJob: Job?        = null
    private var currentState = VoiceState.IDLE

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun hasMicPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    fun toggleRecording() {
        when (currentState) {
            VoiceState.IDLE       -> startRecording()
            VoiceState.RECORDING  -> stopRecording()
            VoiceState.PROCESSING -> { /* انتظر */ }
        }
    }

    private fun setState(s: VoiceState) {
        currentState = s
        onStateChange(s)
    }

    private fun startRecording() {
        if (!hasMicPermission()) {
            onError("Microphone permission required")
            return
        }

        val sessionId = java.util.UUID.randomUUID().toString()
        val request   = Request.Builder()
            .url("wss://$endpoint/mobile/ws/stt/$sessionId")
            .build()

        sttWebSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                setState(VoiceState.RECORDING)
                startAudioCapture(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json  = JSONObject(text)
                    val type  = json.optString("type")
                    val transcript = json.optString("text", "").trim()

                    when (type) {
                        "final" -> {
                            if (transcript.isNotEmpty()) {
                                onTranscript(transcript)   // ← حط الكلام في الـ input
                            }
                            setState(VoiceState.IDLE)
                        }
                        "partial" -> {
                            // ممكن تعرضه كـ preview لو حبيت
                        }
                        "error" -> {
                            onError(transcript)
                            setState(VoiceState.IDLE)
                        }
                    }
                } catch (e: Exception) {
                    onError("Parse error: ${e.message}")
                    setState(VoiceState.IDLE)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                onError("Connection failed: ${t.message}")
                setState(VoiceState.IDLE)
                stopAudioCapture()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                setState(VoiceState.IDLE)
            }
        })
    }

    private fun startAudioCapture(ws: WebSocket) {
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL_CFG, AUDIO_FORMAT, BUFFER_SIZE
        )
        audioRecord?.startRecording()

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(BUFFER_SIZE)
            try {
                while (isActive && currentState == VoiceState.RECORDING) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        ws.send(okio.ByteString.of(*buffer.copyOf(read)))
                    }
                }
            } finally {
                stopAudioCapture()
            }
        }
    }

    fun stopRecording() {
        setState(VoiceState.PROCESSING)
        recordingJob?.cancel()
        stopAudioCapture()
        // بعت stop signal للسيرفر
        sttWebSocket?.send("""{"type":"stop"}""")
    }

    private fun stopAudioCapture() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }

    fun release() {
        stopAudioCapture()
        recordingJob?.cancel()
        sttWebSocket?.close(1000, "Released")
    }
}