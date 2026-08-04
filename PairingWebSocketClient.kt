package com.example.app_abdelbaset

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PairingWebSocketClient(
    private val context: Context,
    private val serverWsUrl: String,
    private val deviceId: String,
    private val accessToken: String,
    private val deviceName: String = "Android-Phone",
    private val pairedDevices: List<Pair<String, String>> = emptyList(),
    private val onTaskReceived: (JSONObject) -> Unit,
    private val onPeerOnline: (String, String) -> Unit,
    private val onPeerOffline: (String) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onStatusChange: (Boolean) -> Unit
) {
    companion object {
        private const val TAG = "PairingWS"
        private const val RECONNECT_DELAY_MS = 5000L
        private const val MAX_RECONNECT_DELAY_MS = 30000L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isConnected = AtomicBoolean(false)
    private var shouldReconnect = true
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null

    fun connect() {
        shouldReconnect = true
        doConnect()
    }

    private fun doConnect() {
        val url = "$serverWsUrl/api/pairing/ws"
        Log.d(TAG, "Connecting to $url")

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "Connected")
                isConnected.set(true)
                reconnectAttempt = 0
                onStatusChange(true)

                val register = JSONObject().apply {
                    put("type", "register")
                    put("device_id", deviceId)
                    put("token", accessToken)
                    put("name", deviceName)
                    put("device_type", "mobile")
                    if (pairedDevices.isNotEmpty()) {
                        val arr = org.json.JSONArray()
                        pairedDevices.forEach { (pdId, pdName) ->
                            arr.put(JSONObject().apply {
                                put("device_id", pdId)
                                put("name", pdName)
                                put("device_type", "desktop")
                            })
                        }
                        put("paired_devices", arr)
                    }
                }
                ws.send(register.toString())

                pingJob?.cancel()
                pingJob = scope.launch {
                    while (isActive) {
                        delay(20000)
                        try {
                            val ping = JSONObject().apply { put("type", "ping") }
                            ws.send(ping.toString())
                            Log.d(TAG, "Ping sent")
                        } catch (e: Exception) {
                            Log.e(TAG, "Ping failed: ${e.message}")
                        }
                    }
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "registered" -> {
                            Log.d(TAG, "Registered as $deviceId")
                        }
                        "task" -> {
                            Log.d(TAG, "Task received: ${json.optString("action")}")
                            onTaskReceived(json)
                        }
                        "peer_online" -> {
                            val peerId = json.optString("device_id", "")
                            val name = json.optString("name", "Unknown")
                            onPeerOnline(peerId, name)
                        }
                        "peer_offline" -> {
                            val peerId = json.optString("device_id", "")
                            onPeerOffline(peerId)
                        }
                        "pong" -> { }
                        "device_disconnected" -> {
                            Log.d(TAG, "Device disconnected by peer")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Parse error: ${e.message}")
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Failure: ${t.message}")
                isConnected.set(false)
                pingJob?.cancel()
                onStatusChange(false)
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Closed: $code $reason")
                isConnected.set(false)
                pingJob?.cancel()
                onStatusChange(false)
                onDisconnected()
            }
        })
    }

    fun sendTaskResult(taskId: String, status: String, data: JSONObject = JSONObject(), originalSender: String? = null) {
        val payload = JSONObject().apply {
            put("type", "task_result")
            put("task_id", taskId)
            put("status", status)
            put("data", data)
            if (originalSender != null) put("original_sender", originalSender)
        }
        webSocket?.send(payload.toString())
        Log.d(TAG, "Sent task result: $taskId -> $status")
    }

    fun updateName(name: String) {
        val payload = JSONObject().apply {
            put("type", "update_name")
            put("name", name)
        }
        webSocket?.send(payload.toString())
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delay = minOf(
                RECONNECT_DELAY_MS * (1 shl minOf(reconnectAttempt, 5)),
                MAX_RECONNECT_DELAY_MS
            )
            reconnectAttempt++
            Log.d(TAG, "Reconnecting in ${delay}ms (attempt $reconnectAttempt)")
            delay(delay)
            if (shouldReconnect) {
                doConnect()
            }
        }
    }

    fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        pingJob?.cancel()
        webSocket?.close(1000, "Disconnecting")
        webSocket = null
        isConnected.set(false)
        onStatusChange(false)
    }

    fun isCurrentlyConnected(): Boolean = isConnected.get()

    fun release() {
        disconnect()
        scope.cancel()
        client.dispatcher.executorService.shutdown()
    }
}
