package com.example.app_abdelbaset

import android.util.Log
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate

/**
 * VadDetector - FIXED VERSION
 *
 * الإصلاح: رفع silenceDurationMs من 1200ms لـ 2000ms
 * عشان ما يبعتش stop signal بسرعة قبل ما AssemblyAI يتمكن يحدد نهاية الكلام
 */
class VadDetector(
    val onSpeechStart: () -> Unit = {},
    val onSpeechEnd: () -> Unit = {}
) {

    companion object {
        private const val TAG = "VadDetector"

        val FRAME_SIZE  = FrameSize.FRAME_SIZE_320
        val SAMPLE_RATE = SampleRate.SAMPLE_RATE_16K

        /** 320 samples × 2 bytes = 640 bytes per frame */
        val FRAME_BYTES = FRAME_SIZE.value * 2
    }

    private val vad = VadWebRTC(
        sampleRate        = SAMPLE_RATE,
        frameSize         = FRAME_SIZE,
        mode              = Mode.AGGRESSIVE,
        silenceDurationMs = 2000,  // ← FIXED: was 1200ms, now 2000ms
        speechDurationMs  = 90
    )

    private var speechActive = false

    init {
        Log.d(TAG, "VadDetector ready  frameBytes=$FRAME_BYTES  silence=2000ms  speech=90ms")
    }

    fun processFrame(pcmBytes: ByteArray): Boolean {
        val isSpeech = try {
            vad.isSpeech(pcmBytes)
        } catch (e: Exception) {
            Log.w(TAG, "VAD frame error: ${e.message}")
            return false
        }

        when {
            isSpeech && !speechActive -> {
                speechActive = true
                Log.d(TAG, "▶ Speech START")
                onSpeechStart()
            }
            !isSpeech && speechActive -> {
                speechActive = false
                Log.d(TAG, "■ Speech END")
                onSpeechEnd()
            }
        }

        return isSpeech
    }

    fun reset() {
        speechActive = false
        Log.d(TAG, "VAD reset")
    }

    fun release() {
        try {
            vad.close()
            Log.d(TAG, "VAD released")
        } catch (e: Exception) {
            Log.w(TAG, "VAD release error: ${e.message}")
        }
    }

    val isSpeechActive: Boolean get() = speechActive
}