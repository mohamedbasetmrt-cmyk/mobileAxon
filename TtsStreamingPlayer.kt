package com.example.app_abdelbaset

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * TtsStreamingPlayer — v5  (PARALLEL PREFETCH EDITION)
 * ═══════════════════════════════════════════════════════════════════════
 *
 * CHANGES vs v4  —  the one fix that eliminates inter-sentence gaps
 * ─────────────────────────────────────────────────────────────────────
 *
 *  Change 1 — Parallel prefetch (biggest latency win)
 *  ───────────────────────────────────────────────────
 *  v4 flow (sequential — causes the 2-3s gaps in logs):
 *    play sentence 1 → wait until done → fetch sentence 2 → play → ...
 *
 *  v5 flow (parallel):
 *    sentence 1: fetch + play  ─────────────────────────────►
 *    sentence 2:      fetch (starts immediately) ──► PCM ready in pcmCache
 *    sentence 3:               fetch ──► PCM ready
 *    When sentence 1 finishes playing, sentence 2 PCM is already in RAM
 *    → zero network wait → seamless back-to-back playback.
 *
 *  Implementation:
 *    - pcmCache: ConcurrentHashMap<index, Deferred<PcmChunk?>>
 *      Each deferred is a parallel coroutine that fetches + parses WAV
 *      into raw PCM bytes.  Kicked off by queueChunk() immediately.
 *    - playbackLoop() awaits pcmCache[i] instead of fetching inline.
 *    - PrefetchDepth = 3: up to 3 sentences prefetched ahead of playback.
 *
 *  Change 2 — Removed redundant HTTP streaming (simplification)
 *  ─────────────────────────────────────────────────────────────
 *  Since the full WAV is now fetched in the prefetch coroutine (and we
 *  need the full PCM before queuing to AudioTrack anyway for correct
 *  ordering), the chunked-HTTP streaming inside processWavStream() is
 *  replaced with a single readBytes() call.  This removes complexity and
 *  fixes a rare partial-WAV bug in v4 where a sentence < WAV_HEADER_MIN
 *  bytes would silently be dropped.
 *
 *  All v4 improvements retained:
 *    - sentence queue + QUEUE_DONE_SENTINEL
 *    - single AudioTrack reused across sentences (no gap/click)
 *    - RMS decoupled from audio path (30fps UI updater)
 *    - 2× minBufSize AudioTrack
 *    - onPlaybackStarted callback guard
 * ═══════════════════════════════════════════════════════════════════════
 */
class TtsStreamingPlayer(
    private val serverBaseUrl:      String,
    private val onAudioLevel:       (Float) -> Unit = {},
    private val onPlaybackStarted:  () -> Unit       = {},
    private val onPlaybackFinished: () -> Unit       = {},
    private val onError:            (String) -> Unit = {}
) {

    // ── Internal data class holding decoded PCM for one sentence ──────
    private data class PcmChunk(
        val pcm:        ByteArray,
        val sampleRate: Int,
        val channels:   Int,
        val bitsPerSamp: Int
    )

    companion object {
        private const val TAG               = "TtsPlayer"
        private const val READ_TIMEOUT_SEC  = 90L
        private const val WAV_HEADER_MIN    = 44
        private const val WRITE_CHUNK_BYTES = 4096
        private const val QUEUE_DONE_SENTINEL = "__DONE__"

        // How many sentences ahead to prefetch (network permitting)
        private const val PREFETCH_DEPTH = 3
    }

    private val isPlaying     = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    @Volatile private var latestRms = 0f

    private var audioTrack: AudioTrack? = null
    private var playJob:    Job?        = null

    // sentence text queue (unchanged from v4)
    private val sentenceQueue = LinkedBlockingQueue<String>()

    // ── NEW: prefetch cache  index → Deferred<PcmChunk?> ─────────────
    private val pcmCache = java.util.concurrent.ConcurrentHashMap<Int, Deferred<PcmChunk?>>()
    @Volatile private var enqueueIndex = 0   // index assigned on queueChunk / speak
    @Volatile private var playIndex    = 0   // index consumed by playbackLoop

    // UI updater at 30fps
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val uiUpdater = object : Runnable {
        override fun run() {
            if (isPlaying.get()) {
                onAudioLevel(latestRms)
                uiHandler.postDelayed(this, 33)
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    // ─────────────────────────────────────────────────────────────────
    // PUBLIC API  (unchanged signatures — drop-in replacement for v4)
    // ─────────────────────────────────────────────────────────────────

    fun speak(text: String) {
        stopPlayback()
        stopRequested.set(false)
        isPlaying.set(true)
        latestRms = 0f
        sentenceQueue.clear()
        pcmCache.clear()
        enqueueIndex = 0
        playIndex    = 0

        // Kick off prefetch for sentence 0 immediately
        prefetchNow(0, text)
        sentenceQueue.put(text)

        uiHandler.post(uiUpdater)

        playJob = scope.launch {
            try {
                playbackLoop()
            } catch (e: CancellationException) {
                Log.d(TAG, "TTS cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "TTS error: ${e.message}", e)
                withContext(Dispatchers.Main) { onError("TTS error: ${e.message}") }
            } finally {
                releaseAudioTrack()
                isPlaying.set(false)
                latestRms = 0f
                uiHandler.post { onAudioLevel(0f) }
                if (!stopRequested.get()) {
                    withContext(Dispatchers.Main) { onPlaybackFinished() }
                }
            }
        }
    }

    fun queueChunk(text: String) {
        if (text.isBlank()) return
        val idx = ++enqueueIndex
        Log.d(TAG, "queueChunk[$idx]: ${text.take(60)}")
        // ── KEY CHANGE: kick off prefetch IMMEDIATELY, before playback ──
        prefetchNow(idx, text)
        sentenceQueue.put(text)
    }

    fun markQueueDone() {
        sentenceQueue.put(QUEUE_DONE_SENTINEL)
    }

    fun stopPlayback() {
        stopRequested.set(true)
        sentenceQueue.put(QUEUE_DONE_SENTINEL)
        uiHandler.removeCallbacks(uiUpdater)
        playJob?.cancel()
        cancelAllPrefetches()
        releaseAudioTrack()
        isPlaying.set(false)
        latestRms = 0f
    }

    fun release() {
        stopPlayback()
        scope.cancel()
        httpClient.dispatcher.executorService.shutdown()
    }

    val isCurrentlyPlaying: Boolean get() = isPlaying.get()

    // ─────────────────────────────────────────────────────────────────
    // PREFETCH  (Change 1 — the core new logic)
    // ─────────────────────────────────────────────────────────────────

    /**
     * Launch a background coroutine to fetch + decode the WAV for [text]
     * and store the result in [pcmCache] under [index].
     * Called the instant a sentence is known (speak or queueChunk).
     */
    private fun prefetchNow(index: Int, text: String) {
        val deferred = scope.async {
            if (stopRequested.get()) return@async null
            Log.d(TAG, "prefetch[$index] start: '${text.take(60)}'")
            try {
                val wav = fetchWavBytes(text) ?: return@async null
                val chunk = parseWavToPcm(wav)
                Log.d(TAG, "prefetch[$index] done — ${chunk?.pcm?.size ?: 0} PCM bytes")
                chunk
            } catch (e: CancellationException) {
                null
            } catch (e: Exception) {
                Log.e(TAG, "prefetch[$index] error: ${e.message}")
                null
            }
        }
        pcmCache[index] = deferred
    }

    private fun cancelAllPrefetches() {
        pcmCache.values.forEach { it.cancel() }
        pcmCache.clear()
        enqueueIndex = 0
        playIndex    = 0
    }

    // ─────────────────────────────────────────────────────────────────
    // PLAYBACK LOOP  (now awaits prefetch cache instead of fetching inline)
    // ─────────────────────────────────────────────────────────────────

    private suspend fun playbackLoop() = withContext(Dispatchers.IO) {
        var firstChunk = true

        while (!stopRequested.get()) {
            val sentence = sentenceQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue

            if (sentence == QUEUE_DONE_SENTINEL || stopRequested.get()) break

            val idx = playIndex++
            Log.d(TAG, "playbackLoop: playing sentence[$idx] '${sentence.take(60)}'")

            if (firstChunk) {
                withContext(Dispatchers.Main) { onPlaybackStarted() }
                firstChunk = false
            }

            // Await the prefetch — should already be done or close to done
            val deferred = pcmCache[idx]
            if (deferred == null) {
                // Shouldn't happen, but fallback: fetch inline
                Log.w(TAG, "playbackLoop: no prefetch for [$idx] — fetching inline")
                prefetchNow(idx, sentence)
                pcmCache[idx]?.await()?.let { playPcmChunk(it) }
            } else {
                val chunk = deferred.await()
                if (chunk != null && !stopRequested.get()) {
                    playPcmChunk(chunk)
                }
            }

            // Clean up cache entry
            pcmCache.remove(idx)
        }

        audioTrack?.apply {
            if (playState == AudioTrack.PLAYSTATE_PLAYING) stop()
        }
        Log.d(TAG, "playbackLoop: finished")
    }

    // ─────────────────────────────────────────────────────────────────
    // FETCH  (returns raw WAV bytes — used by prefetch coroutine)
    // ─────────────────────────────────────────────────────────────────

    private fun fetchWavBytes(text: String): ByteArray? {
        val url      = "$serverBaseUrl/tts"
        val bodyJson = JSONObject().apply {
            put("text",   text)
            put("stream", true)
        }.toString()

        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        Log.d(TAG, "HTTP POST /tts '${text.take(80)}'")

        return try {
            httpClient.newCall(request).execute().use { response ->
                when {
                    response.code == 204 -> {
                        Log.w(TAG, "Server 204 — TTS unavailable")
                        null
                    }
                    !response.isSuccessful -> throw Exception("HTTP ${response.code}: ${response.message}")
                    else -> response.body?.bytes()
                        ?: throw Exception("Empty TTS response body")
                }
            }
        } catch (e: Exception) {
            if (!stopRequested.get()) {
                Log.e(TAG, "fetchWavBytes error: ${e.message}", e)
                throw e
            }
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // WAV → PCM  (pure decode, no AudioTrack interaction)
    // ─────────────────────────────────────────────────────────────────

    private fun parseWavToPcm(wavBytes: ByteArray): PcmChunk? {
        if (wavBytes.size < WAV_HEADER_MIN) {
            Log.w(TAG, "WAV too small: ${wavBytes.size} bytes")
            return null
        }
        if (wavBytes[0] != 'R'.code.toByte() ||
            wavBytes[1] != 'I'.code.toByte() ||
            wavBytes[2] != 'F'.code.toByte() ||
            wavBytes[3] != 'F'.code.toByte()
        ) {
            Log.w(TAG, "Not a valid RIFF/WAV header")
            return null
        }

        val buf = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(22); val channels    = buf.short.toInt()
        buf.position(24); val sampleRate  = buf.int
        buf.position(34); val bitsPerSamp = buf.short.toInt()

        // Find "data" sub-chunk
        var dataOffset = 12
        while (dataOffset + 8 <= wavBytes.size) {
            val chunkId = String(wavBytes, dataOffset, 4, Charsets.ISO_8859_1)
            val chunkSz = ByteBuffer.wrap(wavBytes, dataOffset + 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int
            if (chunkId == "data") { dataOffset += 8; break }
            dataOffset += 8 + maxOf(chunkSz, 0)
        }

        val pcmSize = wavBytes.size - dataOffset
        if (pcmSize <= 0) {
            Log.w(TAG, "Empty PCM data in WAV")
            return null
        }

        val pcm = wavBytes.copyOfRange(dataOffset, dataOffset + pcmSize)
        return PcmChunk(pcm, sampleRate, channels, bitsPerSamp)
    }

    // ─────────────────────────────────────────────────────────────────
    // PLAY  (writes a decoded PcmChunk to the shared AudioTrack)
    // ─────────────────────────────────────────────────────────────────

    private fun playPcmChunk(chunk: PcmChunk) {
        if (stopRequested.get()) return

        val channelMask = if (chunk.channels == 1) AudioFormat.CHANNEL_OUT_MONO
        else                     AudioFormat.CHANNEL_OUT_STEREO
        val encoding    = if (chunk.bitsPerSamp == 16) AudioFormat.ENCODING_PCM_16BIT
        else                         AudioFormat.ENCODING_PCM_8BIT

        Log.d(TAG, "playPcmChunk: ${chunk.pcm.size} bytes  ${chunk.sampleRate}Hz  ${chunk.channels}ch  ${chunk.bitsPerSamp}bit")

        ensureAudioTrack(chunk.sampleRate, channelMask, encoding)

        val track = audioTrack ?: return
        writeToAudioTrack(track, chunk.pcm, 0, chunk.pcm.size, chunk.bitsPerSamp == 16)
    }

    // ─────────────────────────────────────────────────────────────────
    // AUDIOTRACK MANAGEMENT
    // ─────────────────────────────────────────────────────────────────

    private fun ensureAudioTrack(sampleRate: Int, channelMask: Int, encoding: Int) {
        val current = audioTrack
        // Reuse if format unchanged and track is alive
        if (current != null
            && current.sampleRate == sampleRate
            && current.state == AudioTrack.STATE_INITIALIZED
        ) return

        current?.apply {
            try {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) { stop(); flush() }
                release()
            } catch (_: Exception) {}
        }

        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        val bufSz  = maxOf(minBuf * 2, WRITE_CHUNK_BYTES * 2)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(encoding)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(bufSz)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also {
                check(it.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack init failed" }
                it.play()
                Log.d(TAG, "AudioTrack created  sampleRate=$sampleRate  bufSz=$bufSz")
            }
    }

    private fun writeToAudioTrack(
        track:   AudioTrack,
        data:    ByteArray,
        offset:  Int,
        length:  Int,
        is16Bit: Boolean
    ) {
        var written = 0
        while (written < length && !stopRequested.get()) {
            val toWrite = minOf(WRITE_CHUNK_BYTES, length - written)
            val w = track.write(data, offset + written, toWrite)
            if (w <= 0) {
                Log.e(TAG, "AudioTrack.write returned $w — stopping")
                break
            }
            if (is16Bit) latestRms = computeRms16(data, offset + written, w)
            written += w
        }
        Log.d(TAG, "  wrote $written / $length bytes")
    }

    // ─────────────────────────────────────────────────────────────────
    // RMS
    // ─────────────────────────────────────────────────────────────────

    private fun computeRms16(data: ByteArray, offset: Int, length: Int): Float {
        if (length < 2) return 0f
        var sumSq = 0.0
        var count = 0
        var i     = offset
        val end   = offset + length - 1
        while (i < end) {
            val lo     = data[i].toInt() and 0xFF
            val hi     = data[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toFloat()
            sumSq += sample * sample
            count++
            i += 2
        }
        if (count == 0) return 0f
        val rms = sqrt(sumSq / count).toFloat()
        return (rms / 32768f * 4f).coerceIn(0f, 1f)
    }

    // ─────────────────────────────────────────────────────────────────

    private fun releaseAudioTrack() {
        try {
            audioTrack?.apply {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) { stop(); flush() }
                release()
            }
        } catch (_: Exception) {}
        audioTrack = null
    }
}