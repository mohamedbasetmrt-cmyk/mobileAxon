package com.example.app_abdelbaset

enum class LlmMode { SERVER, LOCAL }

enum class LocalModelState { UNLOADED, LOADING, LOADED, ERROR }

data class AiReference(
    val title: String,
    val url: String,
    val description: String = ""
)

interface LlmProvider {
    fun sendMessage(
        json:       String,
        onChunk:    (String) -> Unit,
        onDone:     () -> Unit,
        onError:    (String) -> Unit,
        onAction:   (List<org.json.JSONObject>) -> Unit = {},
        onImage:    (android.graphics.Bitmap) -> Unit = {},
        onReferences: (List<AiReference>) -> Unit = {}
    )
    fun connect(onConnected: () -> Unit = {}) {}
    fun disconnect() {}
    fun cancel() {}
    val isReady: Boolean
}