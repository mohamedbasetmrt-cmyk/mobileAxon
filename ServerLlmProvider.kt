package com.example.app_abdelbaset

import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.example.app_abdelbaset.SystemPromptManager
import com.axon.mobile.core.memory.LearningMemoryManager

class ServerLlmProvider(
    private val endpoint:      String,
    private val onConnected:   () -> Unit,
    private val onDisconnected: () -> Unit
) : LlmProvider {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var webSocket:     WebSocket? = null
    private var pendingJson:   String?    = null
    private var pendingChunk:  ((String) -> Unit)? = null
    private var pendingDone:   (() -> Unit)?        = null
    private var pendingError:  ((String) -> Unit)?  = null
    private var _isReady = false
    
    @Volatile private var userDisconnected = false

    override val isReady: Boolean get() = _isReady

    override fun connect(onConnected: () -> Unit) {
        userDisconnected = false  // Reset on explicit connect
        
        val sessionId = java.util.UUID.randomUUID().toString()
        val request   = Request.Builder()
            .url("wss://$endpoint/mobile/ws/llm/$sessionId")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                _isReady = true
                this@ServerLlmProvider.onConnected()
                onConnected()
                pendingJson?.let { ws.send(it); pendingJson = null }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json    = JSONObject(text)
                    val msgType = json.optString("type")
                    val chunk   = json.optString("text", json.optString("content", ""))
                    if (msgType == "done") {
                        pendingDone?.invoke()
                        return
                    }
                    if (chunk.isNotEmpty()) pendingChunk?.invoke(chunk)
                } catch (e: Exception) {
                    pendingChunk?.invoke(text)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                _isReady = false
                webSocket = null
                onDisconnected()
                pendingError?.invoke(t.message ?: "Connection failed")
                
                // Only auto-reconnect if user hasn't explicitly disconnected
                if (!userDisconnected) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        connect {}
                    }, 2000)
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                _isReady = false
                onDisconnected()
            }
        })
    }

    override fun sendMessage(
        json:    String,
        onChunk: (String) -> Unit,
        onDone:  () -> Unit,
        onError: (String) -> Unit,
        onAction: (List<JSONObject>) -> Unit
    ) {
        pendingChunk = onChunk
        pendingDone  = onDone
        pendingError = onError

        // ── inject context if available ──
        val enrichedJson = try {
            val obj = JSONObject(json)
            val ctx = SystemPromptManager.getContextReference()
            val learned = LearningMemoryManager.getBlock()
            val combined = when {
                ctx.isNotBlank() && learned.isNotBlank() -> "$ctx\n\n$learned"
                ctx.isNotBlank() -> ctx
                learned.isNotBlank() -> learned
                else -> ""
            }
            if (combined.isNotBlank() && !obj.has("context")) {
                obj.put("context", combined)
            }
            obj.toString()
        } catch (_: Exception) { json }

        if (!_isReady || webSocket == null) {
            pendingJson = enrichedJson
            connect {}
        } else {
            webSocket?.send(enrichedJson)
        }
    }

    override fun disconnect() {
        userDisconnected = true
        webSocket?.close(1000, "Disconnected")
        webSocket = null
        _isReady  = false
    }
}