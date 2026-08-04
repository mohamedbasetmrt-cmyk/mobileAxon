package com.example.app_abdelbaset

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * LlmStreamingClient — v3
 * ═══════════════════════════════════════════════════════════════════════
 *
 * CHANGES vs v2:
 *
 *  Change 1 — Added onSentence callback
 *    Server now sends {"type":"sentence","text":"..."} for each completed
 *    sentence BEFORE the full response is done. This lets AxonVoiceSession
 *    start TTS immediately on the first sentence instead of waiting for
 *    the entire LLM response.
 *
 *  Change 2 — Added onProgress callback
 *    Server sends {"type":"progress","text":"..."} while the agent is
 *    working (tool calls, memory lookups, etc.). Forwarded to caller so
 *    the UI can show a thinking indicator.
 *
 *  Protocol (updated):
 *    SEND → {"text": "user message"}
 *    RECV ← {"type":"progress",  "text":"On it... (1/3)"}   ← NEW
 *    RECV ← {"type":"sentence",  "text":"First sentence."}  ← NEW
 *    RECV ← {"type":"sentence",  "text":"Second sentence."} ← NEW (optional)
 *    RECV ← {"type":"done",      "text":"full response"}
 *    RECV ← {"type":"error",     "text":"..."}
 *    RECV ← {"type":"token",     "text":"..."}  (legacy — still handled)
 *
 * ═══════════════════════════════════════════════════════════════════════
 */
class LlmStreamingClient(
    private val serverBaseUrl:  String,
    private val sessionId:      String,
    private val onToken:        (String) -> Unit = {},
    private val onSentence:     (String) -> Unit = {},   // NEW — first sentence arrives early
    private val onProgress:     (String) -> Unit = {},   // NEW — agent progress indicator
    private val onAction:       (JSONObject) -> Unit = {},   // NEW
    private val onDone:         (String) -> Unit,
    private val onError:        (String) -> Unit
) {

    companion object {
        private const val TAG = "LlmClient"

        // Set to true ONLY during development to bypass TLS cert check.
        // Keep in sync with AudioStreamingManager.DEBUG_TRUST_ALL_CERTS.
        private const val DEBUG_TRUST_ALL_CERTS = false

        private const val CONNECT_TIMEOUT_MS = 15_000L
    }

    private var webSocket:         WebSocket?                    = null
    private val isConnected      = AtomicBoolean(false)
    private val scope            = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectDeferred: CompletableDeferred<Boolean>?   = null

    private val httpClient: OkHttpClient by lazy { buildHttpClient() }

    // ─────────────────────────────────────────────────────────────────────
    private fun buildHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)   // no read timeout — streaming
            .pingInterval(20, TimeUnit.SECONDS)

        if (DEBUG_TRUST_ALL_CERTS) {
            Log.w(TAG, "DEBUG_TRUST_ALL_CERTS=true — DISABLE IN PRODUCTION!")
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
                override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val ssl = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustAll), java.security.SecureRandom())
            }
            builder
                .sslSocketFactory(ssl.socketFactory, trustAll)
                .hostnameVerifier { h, s ->
                    Log.d(TAG, "TLS verify (trust-all): $h  cipher=${s.cipherSuite}")
                    true
                }
        }

        val logging = HttpLoggingInterceptor { msg -> Log.d("OkHttp/LLM", msg) }
            .apply { level = HttpLoggingInterceptor.Level.HEADERS }
        builder.addInterceptor(logging)

        return builder.build()
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────────

    fun connect() {
        if (isConnected.get()) {
            Log.d(TAG, "connect() called but already connected — skipping")
            return
        }

        val url = "$serverBaseUrl/ws/llm/$sessionId"
        Log.d(TAG, "Connecting LLM WebSocket -> $url")

        connectDeferred = CompletableDeferred()
        val request = Request.Builder().url(url).build()
        webSocket = httpClient.newWebSocket(request, createWsListener())
    }

    /**
     * Send user text to the LLM.
     * Waits up to CONNECT_TIMEOUT_MS for the connection if not yet open.
     */
    fun sendMessage(text: String) {
        scope.launch {
            if (!isConnected.get()) {
                Log.d(TAG, "WS not connected — waiting for open (timeout=${CONNECT_TIMEOUT_MS}ms)…")
                val opened = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    connectDeferred?.await()
                } ?: false

                if (!opened) {
                    Log.e(TAG, "LLM WS did not open in time — attempting reconnect")
                    disconnect()
                    connect()
                    val reopened = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                        connectDeferred?.await()
                    } ?: false
                    if (!reopened) {
                        Log.e(TAG, "LLM reconnect also failed")
                        withContext(Dispatchers.Main) {
                            onError("LLM connection failed — check server and network")
                        }
                        return@launch
                    }
                }
            }
            doSend(text)
        }
    }

    fun disconnect() {
        Log.d(TAG, "disconnect() called")
        webSocket?.close(1000, "Done")
        webSocket = null
        isConnected.set(false)
        connectDeferred?.cancel()
        connectDeferred = null
    }

    fun release() {
        disconnect()
        scope.cancel()
        httpClient.dispatcher.executorService.shutdown()
        Log.d(TAG, "LlmStreamingClient released")
    }

    // ─────────────────────────────────────────────────────────────────────
    private fun doSend(text: String) {
        val payload = JSONObject().apply { put("text", text) }.toString()
        val ok = webSocket?.send(payload)
        if (ok == false || ok == null) {
            Log.e(TAG, "WebSocket.send() failed — WS might be closed")
            scope.launch(Dispatchers.Main) { onError("LLM send failed — connection lost") }
        } else {
            Log.d(TAG, "Sent to LLM: ${text.take(80)}…")
        }
    }

    private fun createWsListener() = object : WebSocketListener() {

        override fun onOpen(ws: WebSocket, response: Response) {
            Log.d(TAG, "LLM WebSocket OPEN  code=${response.code}")
            isConnected.set(true)
            connectDeferred?.complete(true)
        }

        override fun onMessage(ws: WebSocket, text: String) {
            try {
                val json = JSONObject(text)
                when (json.optString("type")) {

                    // ── NEW: agent progress indicator ──────────────────
                    "progress" -> {
                        val msg = json.optString("text", "")
                        if (msg.isNotEmpty()) {
                            Log.d(TAG, "LLM progress: $msg")
                            scope.launch(Dispatchers.Main) { onProgress(msg) }
                        }
                    }

                    // ── NEW: first/subsequent sentence ready for TTS ───
                    "sentence" -> {
                        val sentence = json.optString("text", "")
                        if (sentence.isNotEmpty()) {
                            Log.d(TAG, "LLM sentence: ${sentence.take(80)}")
                            scope.launch(Dispatchers.Main) { onSentence(sentence) }
                        }
                    }

                    // ── legacy token-by-token (still supported) ────────
                    "token" -> {
                        val token = json.optString("text", "")
                        if (token.isNotEmpty()) {
                            scope.launch(Dispatchers.Main) { onToken(token) }
                        }
                    }
                    // ── mobile action command ──────────────────────────
                    "action" -> {
                        val action = json.optString("action", "unknown")
                        Log.d(TAG, "Mobile action received: $action  params=${json.optJSONObject("params")}")
                        scope.launch(Dispatchers.Main) { onAction(json) }
                    }

                    // ── full response complete ─────────────────────────
                    "done" -> {
                        val full = json.optString("text", "")
                        Log.d(TAG, "LLM done: ${full.take(80)}…")
                        scope.launch(Dispatchers.Main) { onDone(full) }
                    }

                    "error" -> {
                        val err = json.optString("text", "LLM error")
                        Log.e(TAG, "LLM server error: $err")
                        scope.launch(Dispatchers.Main) { onError(err) }
                    }

                    else -> Log.d(TAG, "Unknown LLM message type: $text")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing LLM message: ${e.message}  raw=$text")
            }
        }

        override fun onMessage(ws: WebSocket, bytes: ByteString) {
            Log.w(TAG, "Unexpected binary frame from LLM WS (${bytes.size} bytes) — ignored")
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "LLM WebSocket FAILURE: ${t.javaClass.simpleName}: ${t.message}")
            Log.e(TAG, "  response: code=${response?.code}  msg=${response?.message}")
            isConnected.set(false)
            connectDeferred?.complete(false)
            scope.launch(Dispatchers.Main) {
                onError("LLM connection failed: ${t.message}")
            }
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "LLM WebSocket closed: code=$code  reason=$reason")
            isConnected.set(false)
        }
    }
}
