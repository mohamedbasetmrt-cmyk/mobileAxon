package com.example.app_abdelbaset

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * DeepgramTtsEngine: Cloud-based TTS using Deepgram Aura API.
 * Returns raw PCM (linear16) which is played directly via AudioTrack.
 *
 * ملاحظة: الـ Deepgram API بيعتمد اختيار الصوت عبر `model` بالـ ID الكامل
 * (زي "aura-2-odysseus-en") — مفيش param منفصل اسمه voice.
 */
class DeepgramTtsEngine(
    private val context: Context,
    private val apiKey: String,
    private val voice: String = "aura-2-odysseus-en",
) : TtsEngine {

    companion object {
        private const val TAG = "DeepgramTtsEngine"
        private const val QUEUE_DONE = "__DONE__"
        private const val SAMPLE_RATE = 24000
        private const val DEFAULT_VOICE = "aura-2-odysseus-en"
    }

    // بيحوّل إدخال المستخدم لمعرّف model كامل صالح لـ Deepgram:
    //   "odysseus"           → "aura-2-odysseus-en"  (اسم قصير)
    //   "aura-2-odysseus"    → "aura-2-odysseus-en"  (من غير لغة)
    //   "aura-2-en-daniel"   → "aura-2-daniel-en"    (الصيغة القديمة)
    //   "aura-2-odysseus-en" → كما هو               (id كامل)
    private val resolvedModel: String
        get() {
            val v = voice.trim().ifEmpty { DEFAULT_VOICE }
            return when {
                !v.startsWith("aura-") -> "aura-2-$v-en"
                v.contains("-en-") -> {
                    "aura-2-" + v.substringAfter("-en-") + "-en"
                }
                v.startsWith("aura-2-") && !Regex("-[a-z]{2}$").containsMatchIn(v) -> "$v-en"
                else -> v
            }
        }

    private var isInitialized = false
    private val sentenceQueue = LinkedBlockingQueue<TtsItem>()
    private val isPlaying = AtomicBoolean(false)
    private val pendingSentences = AtomicInteger(0)
    private var utteranceCounter = 0

    // ← حالة نجاح/فشل آخر تشغيل — بيستخدمها الـ caller (مثل إعلان الإشعارات)
    //   عشان يعمل fallback لمحرك تاني لو الـ API فشل.
    @Volatile
    var lastPlaybackFailed: Boolean = false
        private set

    private var audioTrack: AudioTrack? = null
    private val audioTrackLock = Object()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    data class TtsItem(val text: String, val onDone: () -> Unit, val isLast: Boolean = false)

    override fun init(): Boolean {
        if (isInitialized) return true

        if (apiKey.isBlank()) {
            Log.e(TAG, "Deepgram API key is missing")
            return false
        }

        isInitialized = true
        Log.i(TAG, "Deepgram TTS initialized [model=$resolvedModel]")
        return true
    }

    override fun speak(text: String, isLast: Boolean, onDone: () -> Unit) {
        if (!isInitialized && !init()) {
            onDone()
            return
        }

        if (!isPlaying.getAndSet(true)) {
            pendingSentences.incrementAndGet()
            fetchAndPlayInternal(text, generateUtteranceId(), onDone, isLast)
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
        synchronized(audioTrackLock) {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        }
    }

    override fun release() {
        stop()
        client.dispatcher.executorService.shutdown()
        isInitialized = false
    }

    override val isSpeaking: Boolean get() = isPlaying.get()
    override val isReady: Boolean get() = isInitialized

    private fun generateUtteranceId(): String = "deepgram_tts_${utteranceCounter++}"

    private fun fetchAndPlayInternal(text: String, utteranceId: String, onDone: () -> Unit, isLast: Boolean) {
        Thread {
            try {
                lastPlaybackFailed = false
                val url = "https://api.deepgram.com/v1/speak?model=$resolvedModel&encoding=linear16&sample_rate=$SAMPLE_RATE&container=none"

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Token $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(
                        JSONObject().put("text", text).toString()
                            .toRequestBody("application/json".toMediaType())
                    )
                    .build()

                val response = client.newCall(request).execute()

                if (!response.isSuccessful || response.body == null) {
                    Log.e(TAG, "Deepgram API error [$utteranceId]: ${response.code} ${response.message}")
                    lastPlaybackFailed = true
                    response.close()
                    Handler(Looper.getMainLooper()).post { onDone() }
                    cleanupAfterPlayback()
                    processQueue()
                    return@Thread
                }

                val inputStream = response.body!!.byteStream()
                val buffer = ByteArray(4096)
                var bytesRead: Int

                // Initialize AudioTrack if needed
                synchronized(audioTrackLock) {
                    if (audioTrack == null) initAudioTrack()
                }

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (!isPlaying.get()) break

                    // ← تقوية الصوت: خرج الـ Deepgram linear16 بييجي بمستوى واطي
                    if (bytesRead >= 2) applyPcmGain(buffer, bytesRead)

                    synchronized(audioTrackLock) {
                        audioTrack?.write(buffer, 0, bytesRead, AudioTrack.WRITE_BLOCKING)
                    }
                }

                inputStream.close()
                response.close()

                // ← استنى الـ AudioTrack يخلص الـ buffer الداخلي بتاعه قبل الـ stop
                // (الـ write() بتُرجع أول ما البيانات تتحط في الـ buffer، فلو اتعمل
                // stop فوراً آخر ~200ms من الصوت هيتسقط = اقتطاع آخر كلمة)
                waitForTrackDrain()

                Handler(Looper.getMainLooper()).post { onDone() }
                cleanupAfterPlayback()
                processQueue()

            } catch (e: IOException) {
                Log.e(TAG, "Network error [$utteranceId]: ${e.message}", e)
                lastPlaybackFailed = true
                Handler(Looper.getMainLooper()).post { onDone() }
                cleanupAfterPlayback()
                processQueue()
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error [$utteranceId]: ${e.message}", e)
                lastPlaybackFailed = true
                Handler(Looper.getMainLooper()).post { onDone() }
                cleanupAfterPlayback()
                processQueue()
            }
        }.start()
    }

    private fun initAudioTrack() {
        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT) // ← يتبع AI Assistant volume
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
        audioTrack?.setVolume(1.0f) // أقصى حجم للـ track نفسه (الافتراضي كده برضه)
    }

    // ── تقوية مستوى الصوت ──────────────────────────────────────────
    // الـ Aura-2 linear16 بيرجع بمستوى إشارة واطي نسبياً، فبنرفع الـ chunk
    // لحد ما ذروته توصل ~80% من المدى الكامل (مع حماية من الـ clipping).
    // لو الصوت أصلاً عالي (ذروة ≥ 75%) بنسيب chunk زي ما هو عشان منحرفش.
    private fun applyPcmGain(buffer: ByteArray, bytesRead: Int) {
        var peak = 0
        var i = 0
        while (i + 1 < bytesRead) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val absSample = if (sample < 0) -sample else sample
            if (absSample > peak) peak = absSample
            i += 2
        }
        if (peak == 0) return

        val target = (Short.MAX_VALUE * 0.8).toInt()
        val gain = minOf(target.toDouble() / peak, 6.0)
        if (gain <= 1.05) return // مفيش حاجة نرفعها

        i = 0
        while (i + 1 < bytesRead) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            val scaled = (sample * gain).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer[i] = (scaled and 0xFF).toByte()
            buffer[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    // ── استكمال الذيل (tail drain) ─────────────────────────────────
    // نستنى مدة الـ buffer الداخلي للـ AudioTrack (+ هامش) قبل ما أي stop
    // يحصل، عشان آخر كلمة تنتهي بنبرة طبيعية. بنتخطى لو حصل barge-in.
    private fun waitForTrackDrain() {
        if (!isPlaying.get()) return // حصل barge-in — متستنيش
        val drainMs = synchronized(audioTrackLock) {
            val track = audioTrack ?: return
            track.bufferSizeInFrames * 1000L / SAMPLE_RATE + 120
        }
        Thread.sleep(drainMs)
    }

    private fun cleanupAfterPlayback() {
        val remaining = pendingSentences.decrementAndGet()
        if (remaining <= 0) {
            isPlaying.set(false)
            synchronized(audioTrackLock) {
                audioTrack?.stop()
                audioTrack?.release()
                audioTrack = null
            }
        }
    }

    private fun processQueue() {
        if (!isPlaying.get()) return

        val item = sentenceQueue.poll(100, TimeUnit.MILLISECONDS) ?: return

        if (item.text == QUEUE_DONE) {
            if (pendingSentences.get() <= 0) {
                isPlaying.set(false)
                synchronized(audioTrackLock) {
                    audioTrack?.stop()
                    audioTrack?.release()
                    audioTrack = null
                }
                Handler(Looper.getMainLooper()).post { item.onDone() }
            } else {
                sentenceQueue.put(item)
            }
            return
        }

        fetchAndPlayInternal(item.text, generateUtteranceId(), item.onDone, item.isLast)
    }
}