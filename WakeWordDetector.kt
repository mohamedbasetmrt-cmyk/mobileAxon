package com.example.app_abdelbaset

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real-time wake word detector — 3-stage TFLite pipeline.
 *
 *   PCM-16 audio
 *     └─► melspectrogram.tflite  [1, samples]       → [1, 1, mel_time, 32]
 *     └─► embedding_model.tflite [1, 32, 1, 76]     → [1, 1, 1, 96]
 *     └─► ak_son.tflite          [1, 16, 96]        → [1, 1]  score
 */
class WakeWordDetector(
    private val context: Context,
    private val threshold: Float = 0.5f,
    private val debounceMs: Long = 1500L,
    private val onWakeWordDetected: (() -> Unit)? = null,
    private val onError: ((Throwable) -> Unit)?   = null,
    private val onScoreUpdate: ((Float) -> Unit)? = null,
) {

    private val SAMPLE_RATE      = 16_000
    private val EMBEDDING_WINDOW = 76
    private val EMBEDDING_STRIDE = 8
    private val MIN_EMBEDDINGS   = 16
    private val CHUNK_SAMPLES    = 32_000
    private val HOP_SAMPLES      = 1_280
    private val MEL_BINS         = 32
    private val EMB_DIM          = 96

    private val TAG = "WakeWordDetector"

    private val running         = AtomicBoolean(false)
    private var audioRecord     : AudioRecord? = null
    private var detectionThread : Thread? = null

    private val pcmRing   = FloatArray(CHUNK_SAMPLES)
    private var pcmHead   = 0
    private var pcmFilled = 0

    private var melInterpreter : Interpreter? = null
    private var embInterpreter : Interpreter? = null
    private var clsInterpreter : Interpreter? = null

    private var lastDetectionTime  = 0L
    private var onDetectedCallback : ((Float) -> Unit)? = null

    // ── Public API ───────────────────────────────────────────────────────────

    fun start(onDetected: (confidence: Float) -> Unit) {
        onDetectedCallback = onDetected
        start()
    }

    fun start() {
        if (running.getAndSet(true)) return

        try {
            loadModels()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load models", e)
            onError?.invoke(e)
            running.set(false)
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, HOP_SAMPLES * 4)
        ).also { it.startRecording() }

        detectionThread = Thread {
            Log.i(TAG, "Detection thread started")
            try {
                runLoop()
            } catch (e: Throwable) {
                Log.e(TAG, "Detection thread crashed", e)
                onError?.invoke(e)
            }
            Log.i(TAG, "Detection thread exited")
        }.apply { name = "WakeWordDetectionThread"; isDaemon = true; start() }
    }

    fun isRunning(): Boolean = running.get()

    fun release() = stop()

    fun stop() {
        running.set(false)
        detectionThread?.join(500)
        detectionThread = null
        audioRecord?.apply { stop(); release() }
        audioRecord = null
        melInterpreter?.close(); melInterpreter = null
        embInterpreter?.close(); embInterpreter = null
        clsInterpreter?.close(); clsInterpreter = null
        Log.i(TAG, "Detector stopped")
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun loadModel(assetName: String): ByteBuffer {
        val bytes = context.assets.open(assetName).use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size)
            .order(ByteOrder.nativeOrder())
            .also { it.put(bytes); it.rewind() }
    }

    private fun loadModels() {
        val opts = Interpreter.Options().apply { setNumThreads(2) }
        melInterpreter = Interpreter(loadModel("wake/melspectrogram_float32.tflite"), opts)
        embInterpreter = Interpreter(loadModel("wake/embedding_model_float32.tflite"), opts)
        clsInterpreter = Interpreter(loadModel("wake/ak_son.tflite"), opts)
        Log.i(TAG, "All 3 TFLite models loaded")
    }

    private fun runLoop() {
        val readBuf = ShortArray(HOP_SAMPLES)
        while (running.get()) {
            val ar = audioRecord ?: break
            val n  = ar.read(readBuf, 0, HOP_SAMPLES)
            if (n <= 0) continue

            for (i in 0 until n) {
                pcmRing[pcmHead] = readBuf[i] / 32768f
                pcmHead = (pcmHead + 1) % CHUNK_SAMPLES
            }
            pcmFilled = minOf(pcmFilled + n, CHUNK_SAMPLES)
            if (pcmFilled < CHUNK_SAMPLES) continue

            val score = runPipeline() ?: continue

            Log.d(TAG, "score=${"%.4f".format(score)}")
            onScoreUpdate?.invoke(score)

            val now = System.currentTimeMillis()
            if (score >= threshold && now - lastDetectionTime >= debounceMs) {
                lastDetectionTime = now
                Log.i(TAG, "Wake word detected! score=${"%.3f".format(score)}")
                pcmFilled = 0
                onDetectedCallback?.invoke(score)
                onWakeWordDetected?.invoke()
            }
        }
    }

    // ── Pipeline ─────────────────────────────────────────────────────────────

    private fun runPipeline(): Float? {

        // ── Stage 1: Mel Spectrogram ─────────────────────────────────────────
        // Input:  [1, 32000]
        // Output: [1, 1, frames, 32] → squeeze + transform → [frames, 32]
        val audio = FloatArray(CHUNK_SAMPLES)
        for (i in 0 until CHUNK_SAMPLES)
            audio[i] = pcmRing[(pcmHead + i) % CHUNK_SAMPLES]

        val melOutShape = melInterpreter!!.getOutputTensor(0).shape()
        val frames      = melOutShape[2]
        val melOutput   = Array(1) { Array(1) { Array(frames) { FloatArray(MEL_BINS) } } }

        try {
            melInterpreter!!.run(arrayOf(audio), melOutput)
        } catch (e: Exception) {
            Log.e(TAG, "Mel error: ${e.message}")
            return null
        }

        // Squeeze [1,1,frames,32] → [frames,32] + transform (/ 10 + 2)
        val melFlat = Array(frames) { f ->
            FloatArray(MEL_BINS) { b ->
                melOutput[0][0][f][b] / 10.0f + 2.0f
            }
        }
        if (frames < EMBEDDING_WINDOW) return null

        // ── Stage 2: Embeddings ──────────────────────────────────────────────
        // Input:  [1, 32, 1, 76]  ← انتبه للترتيب [batch, bins, 1, time]
        // Output: [1, 1, 1, 96]   → squeeze → FloatArray(96)
        val embeddings = mutableListOf<FloatArray>()
        var winStart   = 0

        while (winStart + EMBEDDING_WINDOW <= frames) {
            val winInput = Array(1) {           // batch = 1
                Array(MEL_BINS) { b ->          // 32
                    Array(1) { _ ->             // 1
                        FloatArray(EMBEDDING_WINDOW) { f ->  // 76
                            melFlat[winStart + f][b]
                        }
                    }
                }
            }
            val embOutput = Array(1) { Array(1) { Array(1) { FloatArray(EMB_DIM) } } }

            try {
                embInterpreter!!.run(winInput, embOutput)
                embeddings.add(embOutput[0][0][0].copyOf())
            } catch (e: Exception) {
                Log.e(TAG, "Embedding error: ${e.message}")
                return null
            }
            winStart += EMBEDDING_STRIDE
        }

        if (embeddings.size < MIN_EMBEDDINGS) return null

        // ── Stage 3: Classifier ──────────────────────────────────────────────
        // Input:  [1, 16, 96]
        // Output: [1, 1]
        val last16    = embeddings.takeLast(MIN_EMBEDDINGS)
        val clsInput  = Array(1) { Array(MIN_EMBEDDINGS) { i -> last16[i] } }
        val clsOutput = Array(1) { FloatArray(1) }

        return try {
            clsInterpreter!!.run(clsInput, clsOutput)
            clsOutput[0][0]
        } catch (e: Exception) {
            Log.e(TAG, "Classifier error: ${e.message}")
            null
        }
    }
}