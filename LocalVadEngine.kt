package com.example.app_abdelbaset

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.*
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.concurrent.atomic.AtomicBoolean

class LocalVadEngine(
    private val context: Context,
    private val onSpeechStart: () -> Unit,
    private val onSpeechEnd: () -> Unit,
) {
    companion object {
        private const val TAG = "LocalVadEngine"
        private const val VAD_WINDOW = 512
        private const val SILENCE_TIMEOUT_MS = 250L
    }

    private var vad: Vad? = null
    private var isSpeechActive = false
    private var lastSpeechTime = 0L
    private val isRunning = AtomicBoolean(false)

    fun initFromAssets(): Boolean {
        return try {
            val config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "Vad/silero_vad.onnx",
                    threshold = 0.5F,
                    minSilenceDuration = 0.25F,
                    minSpeechDuration = 0.25F,
                    windowSize = VAD_WINDOW,
                ),
                sampleRate = 16000,
                numThreads = 1,
                provider = "cpu",
            )
            vad = Vad(assetManager = context.assets, config = config)
            Log.i(TAG, "Silero VAD initialized from assets")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init Silero VAD", e)
            false
        }
    }

    fun initFromFile(modelPath: String): Boolean {
        return try {
            val config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = modelPath,
                    threshold = 0.5F,
                    minSilenceDuration = 0.25F,
                    minSpeechDuration = 0.25F,
                    windowSize = VAD_WINDOW,
                ),
                sampleRate = 16000,
                numThreads = 1,
                provider = "cpu",
            )
            vad = Vad(assetManager = null, config = config)
            Log.i(TAG, "Silero VAD initialized from file")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init Silero VAD", e)
            false
        }
    }

    fun start() {
        isRunning.set(true)
        isSpeechActive = false
        lastSpeechTime = System.currentTimeMillis()
        vad?.reset()
        Log.i(TAG, "VAD started")
    }

    fun stop() {
        isRunning.set(false)
        vad?.reset()
        isSpeechActive = false
        Log.i(TAG, "VAD stopped")
    }

    fun release() {
        stop()
        vad?.release()
        vad = null
    }

    fun processAudio(shortBuffer: ShortArray, length: Int) {
        if (!isRunning.get()) return
        val v = vad ?: return

        val samples = FloatArray(length) { shortBuffer[it] / 32768.0f }
        v.acceptWaveform(samples)
        val isSpeech = v.isSpeechDetected()
        v.clear()

        val now = System.currentTimeMillis()

        when {
            isSpeech && !isSpeechActive -> {
                isSpeechActive = true
                lastSpeechTime = now
                Log.d(TAG, "Speech START")
                onSpeechStart()
            }
            isSpeech && isSpeechActive -> {
                lastSpeechTime = now
            }
            !isSpeech && isSpeechActive -> {
                val silenceMs = now - lastSpeechTime
                if (silenceMs >= SILENCE_TIMEOUT_MS) {
                    isSpeechActive = false
                    Log.d(TAG, "Speech END after ${silenceMs}ms silence")
                    onSpeechEnd()
                }
            }
        }
    }

    val isCurrentlyActive: Boolean get() = isSpeechActive
}
