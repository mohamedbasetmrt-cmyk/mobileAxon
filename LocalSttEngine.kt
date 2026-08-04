package com.example.app_abdelbaset

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicBoolean

class LocalSttEngine(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        private const val TAG = "LocalSttEngine"
        private const val SAMPLE_RATE = 16000
        private const val INTERVAL_SEC = 0.1
        const val CHUNK_SAMPLES = (SAMPLE_RATE * INTERVAL_SEC).toInt()
    }

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    var onAudioFrame: ((ShortArray, Int) -> Unit)? = null

    // ── NEW: Keep latest partial text for final result ─────────────────────
    @Volatile
    private var latestPartialText = ""

    fun initFromAssets(): Boolean {
        return try {
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(
                    sampleRate = SAMPLE_RATE,
                    featureDim = 80
                ),
                modelConfig = OnlineModelConfig(
                    paraformer = OnlineParaformerModelConfig(
                        encoder = "Stt/encoder.int8.onnx",
                        decoder = "Stt/decoder.int8.onnx",
                    ),
                    tokens = "Stt/tokens.txt",
                    numThreads = 2,
                    modelType = "paraformer",
                ),
                enableEndpoint = false,
                decodingMethod = "greedy_search",
            )

            recognizer = OnlineRecognizer(
                assetManager = context.assets,
                config = config
            )

            Log.i(TAG, "Paraformer STT initialized")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init Paraformer", e)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    context,
                    "STT Error: ${e::class.simpleName}\n${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }

            false
        }
    }

    fun initFromFiles(
        encoderPath: String,
        decoderPath: String,
        joinerPath: String,
        tokensPath: String,
    ): Boolean {
        return try {
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = encoderPath,
                        decoder = decoderPath,
                        joiner = joinerPath,
                    ),
                    tokens = tokensPath,
                    numThreads = 2,
                    modelType = "zipformer2",
                ),
                enableEndpoint = false,
                decodingMethod = "greedy_search",
            )
            recognizer = OnlineRecognizer(assetManager = null, config = config)
            Log.i(TAG, "sherpa-onnx STT initialized from files")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init sherpa-onnx STT from files", e)
            false
        }
    }

    fun start() {
        if (isRunning.getAndSet(true)) return
        val rec = recognizer ?: run { onError("STT not initialized"); return }

        stream = rec.createStream()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, CHUNK_SAMPLES * 4)
        ).also { it.startRecording() }

        captureThread = thread(true) { captureLoop() }
        Log.i(TAG, "STT capture started")
    }

    // ── MODIFIED: Don't release stream here, just stop recording ───────────
    fun stop() {
        if (!isRunning.getAndSet(false)) return
        captureThread?.join(500)
        captureThread = null
        audioRecord?.apply { stop(); release() }
        audioRecord = null
        // ── REMOVED: stream release from here ──────────────────────────────
        // stream?.release()
        // stream = null
        Log.i(TAG, "STT capture stopped")
    }

    // ── NEW: Separate method to release stream after getting final text ────
    fun releaseStream() {
        stream?.release()
        stream = null
    }

    fun release() {
        stop()
        releaseStream()  // ← Clean up stream on full release
        recognizer?.release()
        recognizer = null
    }

    // ── MODIFIED: Return accumulated partial text as final ─────────────────
    fun getFinalText(): String {
        return latestPartialText
    }

    // ── NEW: Reset accumulated text for next session ───────────────────────
    fun reset() {
        latestPartialText = ""
    }

    val isStreaming: Boolean get() = isRunning.get()

    private fun captureLoop() {
        val s = stream ?: run {
            Log.e(TAG, "captureLoop: stream is NULL")
            return
        }
        val rec = recognizer ?: run {
            Log.e(TAG, "captureLoop: recognizer is NULL")
            return
        }
        val buf = ShortArray(CHUNK_SAMPLES)
        var lastText = ""

        Log.d(TAG, "captureLoop STARTED, stream=$s, recognizer=$rec")

        while (isRunning.get()) {
            val ar = audioRecord ?: break
            val n = ar.read(buf, 0, CHUNK_SAMPLES)
            if (n <= 0) {
                Log.w(TAG, "AudioRecord read returned: $n")
                continue
            }

            onAudioFrame?.invoke(buf, n)

            val samples = FloatArray(n) { buf[it] / 32768.0f }
            s.acceptWaveform(samples, sampleRate = SAMPLE_RATE)

            // ── طباعة تفصيلية لكل خطوة ──────────────────────────────────────
            var decodeCount = 0
            while (rec.isReady(s)) {
                rec.decode(s)
                decodeCount++
            }
            if (decodeCount > 0) {
                Log.v(TAG, "decode() called $decodeCount times")
            }

            val result = rec.getResult(s)
            Log.d(TAG, "getResult() returned: result=$result")
            Log.d(TAG, "getResult().text='${result.text}'")
            Log.d(TAG, "getResult().text.isBlank=${result.text.isBlank()}")
            Log.d(TAG, "getResult().text.length=${result.text.length}")

            val text = result.text
            if (text.isNotBlank() && text != lastText) {
                lastText = text
                latestPartialText = text
                Log.i(TAG, "✓ NEW PARTIAL: [$text]")
                onPartial(text)
            } else if (text.isBlank()) {
                Log.v(TAG, "text is blank, skipping")
            } else {
                Log.v(TAG, "text unchanged: [$text]")
            }
        }

        Log.d(TAG, "captureLoop EXITED, latestPartialText=[$latestPartialText]")
    }
}