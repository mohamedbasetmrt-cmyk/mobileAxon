package com.example.app_abdelbaset

enum class LlmMode { SERVER, LOCAL }

enum class LocalModelState { UNLOADED, LOADING, LOADED, ERROR }

interface LlmProvider {
    fun sendMessage(
        json:       String,
        onChunk:    (String) -> Unit,
        onDone:     () -> Unit,
        onError:    (String) -> Unit,
        onAction:   (List<org.json.JSONObject>) -> Unit = {}
    )
    fun connect(onConnected: () -> Unit = {}) {}
    fun disconnect() {}
    val isReady: Boolean
}