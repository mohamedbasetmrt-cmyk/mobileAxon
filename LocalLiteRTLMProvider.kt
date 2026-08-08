package com.example.app_abdelbaset

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.*
import org.json.JSONObject

class LocalLiteRTLMProvider(private val context: Context) : LlmProvider {

    companion object {
        const val MODEL_PATH =
            "/storage/emulated/0/Android/data/com.example.app_abdelbaset/ModelsA/Gemma_4_E2B_it/6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm"
    }

    private var engine:    Engine? = null
    private var modelState         = LocalModelState.UNLOADED
    private val scope              = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Conversation History ──
    private data class HistoryMessage(
        val role: String,
        val text: String
    )
    private val messageHistory = mutableListOf<HistoryMessage>()
    private val maxHistoryTurns = 10

    var onModelStateChange: ((LocalModelState) -> Unit)? = null

    override val isReady: Boolean
        get() = modelState == LocalModelState.LOADED && engine != null

    fun getModelState() = modelState

    private fun trimHistory() {
        while (messageHistory.size > maxHistoryTurns) {
            messageHistory.removeAt(0)
        }
    }

    fun clearHistory() {
        messageHistory.clear()
    }

    fun loadModel(
        backend:   String = "CPU",
        onSuccess: () -> Unit,
        onError:   (String) -> Unit
    ) {
        if (modelState == LocalModelState.LOADING || modelState == LocalModelState.LOADED) return

        modelState = LocalModelState.LOADING
        onModelStateChange?.invoke(modelState)

        scope.launch {
            try {
                val engineConfig = EngineConfig(
                    modelPath = MODEL_PATH,
                    backend   = if (backend == "GPU") Backend.GPU() else Backend.CPU(),
                    cacheDir  = context.cacheDir.path
                )

                val e = Engine(engineConfig)
                e.initialize()

                engine     = e
                modelState = LocalModelState.LOADED

                withContext(Dispatchers.Main) {
                    onModelStateChange?.invoke(modelState)
                    onSuccess()
                }

            } catch (ex: Exception) {
                modelState = LocalModelState.ERROR
                withContext(Dispatchers.Main) {
                    onModelStateChange?.invoke(modelState)
                    onError("Model load failed: ${ex.message}")
                }
            }
        }
    }

    fun unloadModel() {
        scope.launch {
            try { engine?.close() } catch (_: Exception) {}
            engine     = null
            modelState = LocalModelState.UNLOADED
            withContext(Dispatchers.Main) {
                onModelStateChange?.invoke(modelState)
            }
        }
    }

    override fun sendMessage(
        json:    String,
        onChunk: (String) -> Unit,
        onDone:  () -> Unit,
        onError: (String) -> Unit,
        onAction: (List<JSONObject>) -> Unit
    ) {
        if (!isReady) { onError("Model not loaded"); return }

        val prompt = try {
            val obj  = JSONObject(json)
            val text = obj.optString("text", "")
            text.ifEmpty { "Hello" }
        } catch (e: Exception) { json }

        // Add user message to history
        messageHistory.add(HistoryMessage("user", prompt))
        trimHistory()

        // Build conversation with history
        val systemPrompt = SystemPromptManager.getEffectivePromptWithContext()
        val fullPrompt = buildString {
            if (systemPrompt != null) {
                append("<start_of_turn>user\n$systemPrompt\n\n")
            } else {
                append("<start_of_turn>user\n")
            }
            
            // Add conversation history
            for ((idx, msg) in messageHistory.withIndex()) {
                if (idx > 0) { // Skip first (current) user message, already added above
                    append("<end_of_turn>\n<start_of_turn>${msg.role}\n${msg.text}")
                }
            }
            append("<end_of_turn>\n<start_of_turn>model\n")
        }

        scope.launch {
            try {
                val convConfig = ConversationConfig(
                    samplerConfig = SamplerConfig(
                        topK        = 40,
                        topP        = 0.95,
                        temperature = 0.8
                    )
                )

                engine!!.createConversation(convConfig).use { conversation ->
                    val responseBuilder = StringBuilder()
                    conversation.sendMessageAsync(fullPrompt)
                        .collect { message ->
                            val text = message.toString()
                            responseBuilder.append(text)
                            if (text.isNotEmpty()) {
                                withContext(Dispatchers.Main) { onChunk(text) }
                            }
                        }
                    
                    // Add assistant response to history
                    val assistantResponse = responseBuilder.toString().trim()
                    if (assistantResponse.isNotBlank()) {
                        messageHistory.add(HistoryMessage("assistant", assistantResponse))
                        trimHistory()
                    }
                    
                    withContext(Dispatchers.Main) { onDone() }
                }

            } catch (ex: Exception) {
                withContext(Dispatchers.Main) { onError(ex.message ?: "Inference error") }
            }
        }
    }

    override fun disconnect() {
        unloadModel()
        messageHistory.clear()
    }
}