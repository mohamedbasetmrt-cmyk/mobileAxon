package com.example.app_abdelbaset

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.io.File
import java.io.FileOutputStream
class SherpaTtsEngine(
    private val context: Context,
    private val engineType: TtsEngineType = TtsEngineType.SHERPA_SUPERTONIC
) : TtsEngine {  // <-- Make sure this is present
    companion object {
        private const val TAG = "SherpaTtsEngine"
        private const val QUEUE_DONE = "__DONE__"
        const val DEFAULT_SUPERTONIC_DIR = "tts"
        const val DEFAULT_VITS_DIR = "tts/Piper"
    }

    private var tts: OfflineTts? = null
    private var isInitialized = false

    private val sentenceQueue = LinkedBlockingQueue<TtsItem>()
    private val isPlaying = AtomicBoolean(false)
    private val pendingSentences = AtomicInteger(0)
    private var utteranceCounter = 0

    private var audioTrack: AudioTrack? = null
    private val audioTrackLock = Object()

    var generationConfig = when (engineType) {
        TtsEngineType.SHERPA_SUPERTONIC -> GenerationConfig(
            sid = 7, speed = 1.25f, numSteps = 8, extra = mapOf("lang" to "en")
        )
        TtsEngineType.SHERPA_VITS_PIPER -> GenerationConfig(
            sid = 0, speed = 1.0f, silenceScale = 0.2f
        )
        else -> GenerationConfig(sid = 0, speed = 1.0f)
    }

    var modelDir: String = when (engineType) {
        TtsEngineType.SHERPA_SUPERTONIC -> DEFAULT_SUPERTONIC_DIR
        TtsEngineType.SHERPA_VITS_PIPER -> DEFAULT_VITS_DIR
        else -> DEFAULT_SUPERTONIC_DIR
    }
    var numThreads: Int = when (engineType) {
        TtsEngineType.SHERPA_SUPERTONIC -> 2
        TtsEngineType.SHERPA_VITS_PIPER -> 1
        else -> 2
    }
    var debug: Boolean = true

    data class TtsItem(val text: String, val onDone: () -> Unit, val isLast: Boolean = false)

    override fun init(): Boolean {
        if (isInitialized) return true
        try {
            val modelPath = resolveModelPath()
            val config = when (engineType) {
                TtsEngineType.SHERPA_SUPERTONIC -> createSupertonicConfig(modelPath)
                TtsEngineType.SHERPA_VITS_PIPER -> createVitsConfig(modelPath)
                else -> createSupertonicConfig(modelPath)
            }
            tts = OfflineTts(assetManager = context.assets, config = config)
            isInitialized = true
            Log.i(TAG, "Sherpa TTS [$engineType] initialized. SR=${tts?.sampleRate()}, Speakers=${tts?.numSpeakers()}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Sherpa TTS [$engineType] init failed: ${e.message}", e)
            return false
        }
    }

    private fun createSupertonicConfig(modelPath: String): OfflineTtsConfig {
        return OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = "$modelPath/duration_predictor.onnx",
                    textEncoder = "$modelPath/text_encoder.onnx",
                    vectorEstimator = "$modelPath/vector_estimator.onnx",
                    vocoder = "$modelPath/vocoder.onnx",
                    ttsJson = "$modelPath/tts.json",
                    unicodeIndexer = "$modelPath/unicode_indexer.bin",
                    voiceStyle = "$modelPath/voice.bin",
                ),
                numThreads = numThreads, debug = debug, provider = "cpu"
            )
        )
    }

    private fun createVitsConfig(modelPath: String): OfflineTtsConfig {
        copyDataDir() // ← نادي الـ copy الأول

        val externalDataDir = File(context.getExternalFilesDir(null), "espeak-ng-data")

        return OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "$modelPath/en_US-bryce-medium.onnx",
                    tokens = "$modelPath/tokens.txt",
                    dataDir = externalDataDir.absolutePath, // ← بره Piper
                ),
                numThreads = numThreads, debug = debug, provider = "cpu"
            )
        )
    }

    private fun resolveModelPath(): String {
        val externalDir = context.getExternalFilesDir(null)
        val externalModelDir = java.io.File(externalDir, modelDir)
        if (externalModelDir.exists()) return externalModelDir.absolutePath
        val internalModelDir = java.io.File(context.filesDir, modelDir)
        if (internalModelDir.exists()) return internalModelDir.absolutePath
        return modelDir // للـ assets
    }

    // Add this function to your SherpaTtsEngine class
    // ← NEW: نسخ recursive للـ espeak-ng-data
    private fun copyDataDir() {
        if (engineType != TtsEngineType.SHERPA_VITS_PIPER) return

        val externalDataDir = File(context.getExternalFilesDir(null), "espeak-ng-data")

        if (externalDataDir.exists() && externalDataDir.listFiles()?.isNotEmpty() == true) {
            Log.d(TAG, "espeak-ng-data already exists")
            return
        }

        try {
            copyAssetDir("tts/Piper/espeak-ng-data", externalDataDir)
            Log.i(TAG, "✅ espeak-ng-data copied to: ${externalDataDir.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to copy espeak-ng-data", e)
        }
    }

    // ← NEW: نسخ مجلد كامل recursive
    private fun copyAssetDir(assetPath: String, outDir: File) {
        val assetList = context.assets.list(assetPath) ?: return

        outDir.mkdirs()

        for (item in assetList) {
            val assetItemPath = "$assetPath/$item"
            val outFile = File(outDir, item)

            // جرب نعمل list — لو نجح يبقى ده مجلد
            val subItems = context.assets.list(assetItemPath)

            if (subItems.isNullOrEmpty()) {
                // ملف
                copyAssetFile(assetItemPath, outFile)
            } else {
                // مجلد فرعي
                copyAssetDir(assetItemPath, outFile)
            }
        }
    }

    // ← NEW: نسخ ملف واحد
    private fun copyAssetFile(assetPath: String, outFile: File) {
        context.assets.open(assetPath).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    override fun speak(text: String, isLast: Boolean, onDone: () -> Unit) {
        if (!isInitialized && !init()) { onDone(); return }
        if (!isPlaying.getAndSet(true)) {
            pendingSentences.incrementAndGet()
            speakInternal(text, generateUtteranceId(), onDone, isLast)
        } else {
            queueSentence(text, isLast, onDone)
        }
    }

    override fun queueSentence(text: String, isLast: Boolean, onDone: () -> Unit) {
        if (text.isBlank()) { onDone(); return }
        if (!isPlaying.get()) speak(text, isLast, onDone)
        else {
            pendingSentences.incrementAndGet()
            sentenceQueue.put(TtsItem(text, onDone, isLast))
        }
    }

    override fun markEndOfStream(onAllDone: () -> Unit) {
        sentenceQueue.put(TtsItem(QUEUE_DONE, onAllDone, isLast = true))
    }

    override fun stop() {
        isPlaying.set(false)
        pendingSentences.set(0)
        sentenceQueue.clear()
        synchronized(audioTrackLock) {
            audioTrack?.stop(); audioTrack?.release(); audioTrack = null
        }
    }

    override fun release() {
        stop()
        tts?.release()
        tts = null
        isInitialized = false
    }

    override val isSpeaking: Boolean get() = isPlaying.get()
    override val isReady: Boolean get() = isInitialized

    private fun generateUtteranceId(): String = "sherpa_tts_${utteranceCounter++}"

    private fun speakInternal(text: String, utteranceId: String, onDone: () -> Unit, isLast: Boolean) {
        val engine = tts ?: run { onDone(); return }
        Thread {
            try {
                engine.generateWithConfigAndCallback(
                    text = text,
                    config = generationConfig,
                    callback = { samples ->
                        playPcmSamples(samples)
                        1 // Continue
                    }
                )
                Handler(Looper.getMainLooper()).post { onDone() }
                val remaining = pendingSentences.decrementAndGet()
                if (remaining <= 0) {
                    isPlaying.set(false)
                    synchronized(audioTrackLock) {
                        audioTrack?.stop(); audioTrack?.release(); audioTrack = null
                    }
                }
                processQueue()
            } catch (e: Exception) {
                Log.e(TAG, "TTS error [$engineType]: $utteranceId", e)
                Handler(Looper.getMainLooper()).post { onDone() }
                pendingSentences.decrementAndGet()
                processQueue()
            }
        }.start()
    }

    private fun playPcmSamples(samples: FloatArray) {
        synchronized(audioTrackLock) {
            if (audioTrack == null) initAudioTrack()
            audioTrack?.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        }
    }

    private fun initAudioTrack() {
        val sampleRate = tts?.sampleRate() ?: 22050
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()
    }

    private fun processQueue() {
        if (!isPlaying.get()) return
        val item = sentenceQueue.poll(100, TimeUnit.MILLISECONDS) ?: return
        if (item.text == QUEUE_DONE) {
            if (pendingSentences.get() <= 0) {
                isPlaying.set(false)
                synchronized(audioTrackLock) {
                    audioTrack?.stop(); audioTrack?.release(); audioTrack = null
                }
                Handler(Looper.getMainLooper()).post { item.onDone() }
            } else sentenceQueue.put(item)
            return
        }
        speakInternal(item.text, generateUtteranceId(), item.onDone, item.isLast)
    }

    fun updateGenerationConfig(
        sid: Int? = null, speed: Float? = null, numSteps: Int? = null,
        silenceScale: Float? = null, extra: Map<String, String>? = null
    ) {
        sid?.let { generationConfig = generationConfig.copy(sid = it) }
        speed?.let { generationConfig = generationConfig.copy(speed = it) }
        numSteps?.let { generationConfig = generationConfig.copy(numSteps = it) }
        silenceScale?.let { generationConfig = generationConfig.copy(silenceScale = it) }
        extra?.let { generationConfig = generationConfig.copy(extra = it) }
    }

    fun getSampleRate(): Int = tts?.sampleRate() ?: 0
    fun getNumSpeakers(): Int = tts?.numSpeakers() ?: 0
}