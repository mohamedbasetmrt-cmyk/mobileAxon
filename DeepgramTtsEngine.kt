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

/**
 * DeepgramTtsEngine: Cloud-based TTS using Deepgram Aura API.
 * Returns raw PCM (linear16) which is played directly via AudioTrack.
 */
class DeepgramTtsEngine(
    private val context: Context,
    private val apiKey: String,
    private val voice: String = "aura-2-en-daniel",
    private val model: String = "aura-2"
) : TtsEngine {

    companion object {
        private const val TAG = "DeepgramTtsEngine"
        private const val QUEUE_DONE = "__DONE__"
        private const val SAMPLE_RATE = 24000
    }

    private var isInitialized = false
    private val sentenceQueue = LinkedBlockingQueue<TtsItem>()
    private val isPlaying = AtomicBoolean(false)
    private val pendingSentences = AtomicInteger(0)
    private var utteranceCounter = 0

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
        Log.i(TAG, "Deepgram TTS initialized [voice=$voice, model=$model]")
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
                val url = "https://api.deepgram.com/v1/speak?model=$model&voice=$voice&encoding=linear16&sample_rate=$SAMPLE_RATE&container=none"
                
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Token $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(MediaType.parse("application/json"), JSONObject().put("text", text).toString()))
                    .build()

                val response = client.newCall(request).execute()
                
                if (!response.isSuccessful || response.body == null) {
                    Log.e(TAG, "Deepgram API error [$utteranceId]: ${response.code} ${response.message}")
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
                    
                    synchronized(audioTrackLock) {
                        audioTrack?.write(buffer, 0, bytesRead, AudioTrack.WRITE_BLOCKING)
                    }
                }
                
                inputStream.close()
                response.close()
                
                Handler(Looper.getMainLooper()).post { onDone() }
                cleanupAfterPlayback()
                processQueue()
                
            } catch (e: IOException) {
                Log.e(TAG, "Network error [$utteranceId]: ${e.message}", e)
                Handler(Looper.getMainLooper()).post { onDone() }
                cleanupAfterPlayback()
                processQueue()
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error [$utteranceId]: ${e.message}", e)
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
                    .setUsage(AudioAttributes.USAGE_MEDIA)
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
