package com.example.app_abdelbaset

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LocalTtsEngine(
    private val context: Context,
    private val enginePackage: String? = null
) : TtsEngine {

    companion object {
        private const val TAG = "LocalTtsEngine"
        private const val QUEUE_DONE = "__DONE__"
        // ← زيادة المهلة إلى 15 ثانية (كان 10 ثوانٍ تقريباً بالحلقة القديمة)
        private const val INIT_TIMEOUT_SECONDS = 15L
    }

    private var tts: TextToSpeech? = null
    @Volatile private var isInitialized = false
    private var initError: String? = null

    private val sentenceQueue = LinkedBlockingQueue<TtsItem>()
    private val isPlaying = AtomicBoolean(false)
    private val pendingSentences = AtomicInteger(0)
    private var utteranceCounter = 0

    data class TtsItem(
        val text: String,
        val onDone: () -> Unit,
        val isLast: Boolean = false
    )

    // ← NEW: CountDownLatch للانتظار الفعال بدلاً من sleep/polling
    private var initLatch: CountDownLatch? = null

    private val initListener = TextToSpeech.OnInitListener { status ->
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts
            if (engine != null) {
                engine.language = Locale.US
                engine.setSpeechRate(1.0f)
                engine.setPitch(1.0f)

                val audioAttributes = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                engine.setAudioAttributes(audioAttributes)

                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "Utterance done: $utteranceId")
                        findAndCallCallback(utteranceId)
                        val remaining = pendingSentences.decrementAndGet()
                        Log.d(TAG, "Pending sentences: $remaining")
                        if (remaining <= 0) {
                            isPlaying.set(false)
                        }
                        processQueue()
                    }

                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "TTS error: $utteranceId")
                        findAndCallCallback(utteranceId)
                        pendingSentences.decrementAndGet()
                        processQueue()
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        Log.e(TAG, "TTS error: $utteranceId code=$errorCode")
                        findAndCallCallback(utteranceId)
                        pendingSentences.decrementAndGet()
                        processQueue()
                    }
                })
                isInitialized = true
                Log.i(TAG, "Android TTS initialized with engine: $enginePackage")
            }
        } else {
            initError = "TTS init failed with status $status"
            Log.e(TAG, initError!!)
        }
        // ← NEW: أطلق الـ latch سواء نجح أو فشل
        initLatch?.countDown()
    }

    private val pendingCallbacks = mutableMapOf<String, () -> Unit>()

    private fun findAndCallCallback(utteranceId: String?) {
        utteranceId?.let { id ->
            pendingCallbacks.remove(id)?.invoke()
        }
    }

    override fun init(): Boolean {
        if (isInitialized) return true

        // ← NEW: latch جديد لكل محاولة init
        initLatch = CountDownLatch(1)

        tts = if (enginePackage != null) {
            TextToSpeech(context, initListener, enginePackage)
        } else {
            TextToSpeech(context, initListener)
        }

        // ← NEW: انتظر الـ callback بشكل فعال (بدون polling)
        try {
            val success = initLatch?.await(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS) ?: false
            if (!success) {
                Log.w(TAG, "TTS init timed out after ${INIT_TIMEOUT_SECONDS}s")
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "TTS init interrupted")
            Thread.currentThread().interrupt()
        }

        return isInitialized
    }

    override fun speak(text: String, isLast: Boolean, onDone: () -> Unit) {
        val engine = tts ?: run { onDone(); return }
        if (!isInitialized) { onDone(); return }

        if (!isPlaying.getAndSet(true)) {
            pendingSentences.incrementAndGet()
            speakInternal(text, generateUtteranceId(), onDone, TextToSpeech.QUEUE_FLUSH, isLast)
        } else {
            queueSentence(text, isLast, onDone)
        }
    }

    override fun queueSentence(text: String, isLast: Boolean, onDone: () -> Unit) {
        if (text.isBlank()) {
            onDone()
            return
        }
        if (!isPlaying.get()) {
            speak(text, isLast, onDone)
        } else {
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
        pendingCallbacks.clear()
        tts?.stop()
    }

    override fun release() {
        stop()

        val oldTts = tts
        tts = null
        isInitialized = false

        oldTts?.let { engine ->
            try {
                engine.stop()
                Thread {
                    try {
                        Thread.sleep(300)
                        engine.shutdown()
                        Log.d(TAG, "TTS engine shutdown completed")
                    } catch (_: Exception) {}
                }.start()
            } catch (_: Exception) {}
        }
    }

    override val isSpeaking: Boolean get() = isPlaying.get() || (tts?.isSpeaking == true)
    override val isReady: Boolean get() = isInitialized

    private fun generateUtteranceId(): String {
        return "axon_tts_${utteranceCounter++}"
    }

    private fun speakInternal(
        text: String,
        utteranceId: String,
        onDone: () -> Unit,
        queueMode: Int,
        isLast: Boolean = false
    ) {
        val engine = tts ?: return
        pendingCallbacks[utteranceId] = onDone
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        engine.speak(text, queueMode, params, utteranceId)
    }

    private fun processQueue() {
        if (!isPlaying.get()) return

        val item = sentenceQueue.poll(100, TimeUnit.MILLISECONDS)
        if (item == null) {
            return
        }

        if (item.text == QUEUE_DONE) {
            if (pendingSentences.get() <= 0) {
                isPlaying.set(false)
                Handler(Looper.getMainLooper()).post {
                    item.onDone()
                }
            } else {
                sentenceQueue.put(item)
            }
            return
        }

        speakInternal(item.text, generateUtteranceId(), item.onDone, TextToSpeech.QUEUE_ADD, item.isLast)
    }
}