package com.example.app_abdelbaset

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import com.axon.mobile.core.memory.LearningMemoryManager

class DahlLlmProvider(private val context: Context) : LlmProvider {

    companion object {
        private const val TAG = "DahlProvider"
        private const val PREF_DAHL_API_KEY = "dahl_api_key"
        private const val PREF_DAHL_MODEL = "dahl_model"
        private const val DEFAULT_MODEL = "MiniMaxAI/MiniMax-M2.7"
        private const val API_BASE = "https://inference.dahl.global/v1/chat/completions"

        val AVAILABLE_MODELS = listOf(
            "MiniMaxAI/MiniMax-M2.7",
            "moonshotai/Kimi-K2.6",
            "zai-org/GLM-5.2-FP8"
        )

        private val VISION_CAPABLE_MODELS = setOf(
            "gpt-4o-mini",
            "MiniMaxAI/MiniMax-M2.7"
        )

        // ═══════════════════════════════════════════════════════════════
        //  TOOL 1: device_action
        // ═══════════════════════════════════════════════════════════════
        private val DEVICE_ACTION_TOOL = JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "device_action")
                put("description",
                    "Execute a single action on the user's Android phone (call, open app, " +
                            "send message, set alarm, toggle settings, etc). Call this once per " +
                            "distinct action; call it multiple times in the same turn for multiple actions."
                )
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("action", JSONObject().apply {
                            put("type", "string")
                            put("description", "Action identifier, e.g. 'open_app', 'call', 'set_alarm'.")
                        })
                        put("params", JSONObject().apply {
                            put("type", "string")
                            put("description", "JSON-encoded object string with the action's parameters, e.g. \"{\\\"app_name\\\":\\\"whatsapp\\\"}\". Use \"{}\" if no parameters are needed.")
                        })
                    })
                    put("required", JSONArray().put("action"))
                })
            })
        }

        // ═══════════════════════════════════════════════════════════════
        //  TOOL 2: knowledge_search
        // ═══════════════════════════════════════════════════════════════
        private val KNOWLEDGE_SEARCH_TOOL = JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "knowledge_search")
                put("description",
                    "Search the document knowledge base for relevant information. " +
                            "Use this when the user asks about specific topics, documentation, " +
                            "technical concepts, or anything requiring a knowledge lookup. " +
                            "Returns relevant document content to help you answer accurately."
                )
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply {
                            put("type", "string")
                            put("description", "The search query — be descriptive and specific to get the best results.")
                        })
                    })
                    put("required", JSONArray().put("query"))
                })
            })
        }

        private val KNOWN_ACTIONS = setOf(
            "call", "answer_call", "end_call", "set_alarm", "set_timer", "open_app", "open_url",
            "screen_lock", "screenshot", "volume_up", "volume_down", "volume_set",
            "volume_mute", "volume_unmute", "brightness_set", "wifi_toggle", "bluetooth_toggle",
            "flashlight_toggle", "send_sms", "send_whatsapp", "play_music", "pause_music",
            "next_track", "previous_track", "take_photo", "record_video", "navigate_to",
            "share_location", "airplane_mode", "do_not_disturb", "hotspot_toggle",
            "copy_to_clipboard", "battery_status", "memory_status", "calendar_add_event",
            "weather_check", "search_web", "translate", "reminder_set",
            "contact_add", "contact_search", "email_send", "notes_add", "stopwatch_start",
            "stopwatch_stop", "stopwatch_reset", "dismiss_notification",
            "read_last_notification", "desktop_task", "download_file", "list_files",
            "search_files", "open_file", "delete_file", "play_audio_file", "play_video",
            "show_image", "open_document", "set_screen_timeout", "get_foreground_app",
            "open_whatsapp_chat", "get_network_status", "bluetooth_scan", "start_tracking",
            "geofence_trigger", "get_calendar_events", "reply_notification",
            "start_voice_recognition", "speak_text", "click_ui_element", "scroll_screen",
            "find_text_on_screen", "input_text"
        )

        private val ACTION_PARAM_HINTS = mapOf(
            "calendar_add_event" to
                    """{"title": "string", "day": "today" | "tomorrow" | "day_after_tomorrow" (ONLY these 3 values, never a real date), "time": "HH:mm" 24h (optional, omit to use current time)}. Event duration is always fixed at 30 minutes.""",
            "get_calendar_events" to
                    """{"date": "YYYY-MM-DD"} (optional, omit for today)""",
            "volume_set" to
                    """{"level": integer} — this is the raw stream volume step (device-dependent, usually 0-15), NOT a 0-100 percentage.""",
            "brightness_set" to
                    """{"level": integer 0-255} — NOT a 0-100 percentage.""",
            "set_alarm" to
                    """{"hour": 0-23, "minute": 0-59, "label": "string", "repeat": ["Mon","Tue",...] (optional, exact 3-letter abbreviations only)}""",
            "scroll_screen" to
                    """{"direction": "up" | "down", "amount": integer (pixels, default 500)}""",
            "navigate_to" to
                    """{"destination": "string", "mode": "driving" | "walking" | "transit" | "bicycling"}""",
            "click_ui_element" to
                    """{"text": "...", "content_desc": "...", "resource_id": "..."} — fill ONLY the ONE field you actually have info for, leave the others empty."""
        )

        private val TOOL_MODE_SYSTEM_PROMPT = buildString {
            append("You are Axon, a Personal AI Companion, not just a voice assistant. You have a continuous, evolving relationship with the user.\n\n")

            append("## YOUR CORE PERSONALITY\n")
            append("- You are a close friend: empathetic, casual, and genuinely interested in the user's day.\n")
            append("- You DO NOT act like a customer service agent. Never say 'How can I help you today?'.\n")
            append("- You remember past context. If the user mentions a project they talked about before, follow up on it naturally.\n\n")

            append("## CONVERSATION MODE (Default)\n")
            append("- When the user is talking about their feelings, venting, or just chatting, DO NOT call any tools.\n")
            append("- Example: User: 'I'm tired.' -> You: 'Sounds like you've had a long day. Get some rest.' (DO NOT offer to play music or set alarms).\n")
            append("- Example: User: 'Today was exhausting.' -> You: 'Sounds like you had a rough day. What happened?'\n")
            append("- The conversation itself has value. Not every reply needs to end with an action or a tool call.\n\n")

            append("## RESPONSE LENGTH\n")
            append("- Simple replies (confirmations, casual chat, quick answers) → AT MOST one short line.\n")
            append("- Longer replies are fine ONLY when truly needed (explanations, steps, details the user asked for).\n\n")

            append("## ACTION MODE (Only when explicitly requested)\n")
            append("- You ONLY enter Action Mode when the user explicitly asks you to do something on the device (e.g., 'Open WhatsApp', 'Call mom', 'Set an alarm').\n")
            append("- When you do trigger an action, keep your spoken response natural and brief.\n")
            append("- Example: User: 'Open WhatsApp' -> You: 'Sure, opening WhatsApp now.' + [Trigger device_action tool].\n\n")

            append("## TOOL USAGE\n")
            append("For phone control tasks, you MUST call the `device_action` tool — never call any other tool name for device actions.\n")
            append("Set the `action` field to exactly one of: ${KNOWN_ACTIONS.joinToString(", ")}\n")
            append("The `params` field must always be a valid JSON-encoded STRING (escaped), matching the exact keys shown below for that action.\n")
            if (ACTION_PARAM_HINTS.isNotEmpty()) {
                append("\nActions with a specific params format — follow these exactly, do not invent other keys:\n")
                ACTION_PARAM_HINTS.forEach { (action, format) ->
                    append("- $action: $format\n")
                }
            }
            // ── Knowledge Search ──
            append("\n## Knowledge Search Tool\n")
            append("You have access to a `knowledge_search` tool that searches a document knowledge base.\n")
            append("\n🔴 MANDATORY SEARCH RULE:\n")
            append("You MUST call `knowledge_search` whenever the user's question involves ANY of these topics:\n")
            append("  • Groovy — scripting, code, syntax, examples\n")
            append("  • Jira — administration, configuration, workflows, APIs, plugins\n")
            append("  • JMWE — Jira Misc Workflow Extensions (post functions, conditions, validators)\n")
            append("  • Jira Service Management (JSM) — queues, SLAs, automation, ITSM\n")
            append("\nDo NOT answer from your own knowledge about these 4 topics — ALWAYS search first.\n")
            append("Even if the question seems simple (e.g. \"what is a post function?\"), search first.\n")
            append("Even if the user mentions these keywords in passing, search first.\n")
            append("\nThe tool takes a single `query` parameter — make it descriptive and specific.\n")
            append("Examples of good queries:\n")
            append("  - User: \"how to write a groovy script for JMWE post function\" → query: \"groovy script JMWE post function\"\n")
            append("  - User: \"how to create a queue in JSM\" → query: \"create queue Jira Service Management\"\n")
            append("  - User: \"JMWE validator example\" → query: \"JMWE validator example groovy\"\n")
            append("\nAfter receiving the search results:\n")
            append("  • Use them to provide a detailed, accurate answer.\n")
            append("  • If the search results contain code examples or step-by-step instructions, include them in your answer.\n")
            append("  • If the search results don't fully answer the question, say what you found and what's missing.\n")
            append("\nYou can call both `knowledge_search` and `device_action` in the same turn if needed.\n")
            append("\nFor normal questions NOT related to Groovy/Jira/JMWE/JSM (weather, jokes, general knowledge), just answer naturally in text without calling any tool.")
        }
    }

    // ── Data classes ──
    private data class ToolCallInfo(
        val id: String,
        val name: String,
        val arguments: String
    )

    private data class StreamResult(
        val text: StringBuilder,
        val toolCalls: List<ToolCallInfo>,
        val error: String?
    )

    private val prefs by lazy {
        context.getSharedPreferences("axon_prefs", Context.MODE_PRIVATE)
    }

    private val httpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var _isReady = false
    private var currentJob: Job? = null

    private data class HistoryMessage(
        val role: String,
        val text: String,
        val imageDataUrl: String? = null
    )

    private val messageHistory = mutableListOf<HistoryMessage>()
    private val maxHistoryTurns = 30

    override val isReady: Boolean get() = _isReady

    val apiKey: String?
        get() = prefs.getString(PREF_DAHL_API_KEY, null)?.takeIf { it.isNotBlank() }

    val currentModel: String
        get() = prefs.getString(PREF_DAHL_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL

    fun hasApiKey(): Boolean = !apiKey.isNullOrBlank()

    override fun connect(onConnected: () -> Unit) {
        _isReady = hasApiKey()
        if (_isReady) {
            Log.d(TAG, "Dahl provider ready (model: $currentModel)")
            onConnected()
        } else {
            Log.w(TAG, "Dahl API key not set")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  HELPER: Build messages array from history + system prompt
    // ═══════════════════════════════════════════════════════════════
    private fun buildMessagesArray(systemPrompt: String): JSONArray {
        val messagesArray = JSONArray()
        messagesArray.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })
        for (msg in messageHistory) {
            val contentValue: Any = if (msg.imageDataUrl != null) {
                JSONArray().apply {
                    if (msg.text.isNotBlank()) {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", msg.text)
                        })
                    }
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply {
                            put("url", msg.imageDataUrl)
                        })
                    })
                }
            } else {
                msg.text
            }
            messagesArray.put(JSONObject().apply {
                put("role", if (msg.role == "user") "user" else "assistant")
                put("content", contentValue)
            })
        }
        return messagesArray
    }

    // ═══════════════════════════════════════════════════════════════
    //  HELPER: Stream a single Dahl (OpenAI-compatible) chat request
    // ═══════════════════════════════════════════════════════════════
    private suspend fun streamDahlRequest(
        messagesArray: JSONArray,
        key: String,
        onChunk: (String) -> Unit
    ): StreamResult {
        val bodyString = JSONObject().apply {
            put("model", currentModel)
            put("messages", messagesArray)
            put("tools", JSONArray().put(DEVICE_ACTION_TOOL).put(KNOWLEDGE_SEARCH_TOOL))
            put("stream", true)
        }.toString()

        val requestBody = bodyString.toRequestBody("application/json".toMediaType())

        val request = okhttp3.Request.Builder()
            .url(API_BASE)
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Accept", "text/event-stream")
            .post(requestBody)
            .build()

        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            val errBody = response.body?.string() ?: "unknown error"
            return StreamResult(StringBuilder(), emptyList(), "Dahl HTTP ${response.code}: $errBody")
        }

        val fullResponse = StringBuilder()

        // OpenAI streaming: accumulate tool calls across chunks by index
        val toolCallIds    = mutableMapOf<Int, String>()
        val toolCallNames  = mutableMapOf<Int, String>()
        val toolCallArgsBuilders = mutableMapOf<Int, StringBuilder>()
        var finishReasonSeen = false

        val source = response.body?.source()
            ?: return StreamResult(StringBuilder(), emptyList(), "Empty response body")

        val buffer = okio.Buffer()
        while (!source.exhausted() && currentJob?.isActive == true) {
            source.read(buffer, 8192)
            val chunk = buffer.readUtf8()
            chunk.lines().forEach { line ->
                if (line.startsWith("data:")) {
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]" || data.isEmpty()) return@forEach
                    try {
                        val obj = JSONObject(data)

                        val choices = obj.optJSONArray("choices")
                        if (choices == null || choices.length() == 0) return@forEach

                        val choice = choices.optJSONObject(0) ?: return@forEach
                        val delta  = choice.optJSONObject("delta") ?: return@forEach

                        // ── Tool calls ──
                        val toolCalls = delta.optJSONArray("tool_calls")
                        if (toolCalls != null && toolCalls.length() > 0) {
                            for (i in 0 until toolCalls.length()) {
                                val tc = toolCalls.optJSONObject(i) ?: continue
                                val idx = tc.optInt("index", 0)
                                val fn  = tc.optJSONObject("function") ?: continue

                                val id = tc.optString("id", "")
                                if (id.isNotEmpty()) {
                                    toolCallIds[idx] = id
                                }

                                val name = fn.optString("name", "")
                                if (name.isNotEmpty()) {
                                    toolCallNames[idx] = name
                                }

                                val args = fn.optString("arguments", "")
                                if (args.isNotEmpty()) {
                                    val builder = toolCallArgsBuilders.getOrPut(idx) { StringBuilder() }
                                    builder.append(args)
                                }
                            }
                        }

                        // ── Text content ──
                        val content = delta.optString("content", "")
                        if (content.isNotEmpty()) {
                            fullResponse.append(content)
                            withContext(Dispatchers.Main) { onChunk(content) }
                        }

                        // ── Finish reason ──
                        val finishReason = choice.optString("finish_reason", "")
                        if (finishReason.isNotEmpty()) {
                            finishReasonSeen = true
                        }

                    } catch (_: Exception) {}
                }
            }
        }

        // ── Finalize tool calls ──
        val collectedToolCalls = mutableListOf<ToolCallInfo>()
        for ((idx, builder) in toolCallArgsBuilders) {
            val name = toolCallNames[idx] ?: ""
            val id   = toolCallIds[idx] ?: "call_$idx"
            if (name.isNotBlank()) {
                collectedToolCalls.add(ToolCallInfo(id, name, builder.toString()))
            }
        }

        return StreamResult(fullResponse, collectedToolCalls, null)
    }

    // ═══════════════════════════════════════════════════════════════
    //  MAIN: sendMessage with knowledge_search follow-up support
    // ═══════════════════════════════════════════════════════════════
    override fun sendMessage(
        json:     String,
        onChunk:  (String) -> Unit,
        onDone:   () -> Unit,
        onError:  (String) -> Unit,
        onAction: (List<JSONObject>) -> Unit
    ) {
        val key = apiKey
        if (key.isNullOrBlank()) {
            onError("Dahl API key not set. Go to Settings > Local Models > Dahl API Key")
            return
        }

        val parsedJson = try { JSONObject(json) } catch (e: Exception) { null }

        val userText  = parsedJson?.optString("text", "") ?: json
        val imageB64  = parsedJson?.optString("image", "")?.takeIf { it.isNotBlank() }
        val mediaType = parsedJson?.optString("media_type", "")?.takeIf { it.isNotBlank() }
            ?: "image/jpeg"

        if (userText.isBlank() && imageB64 == null) { onError("Empty message"); return }

        if (imageB64 != null && currentModel !in VISION_CAPABLE_MODELS) {
            onError(
                "الموديل الحالي ($currentModel) مش بيدعم الصور. " +
                        "روح على Settings > Local Models > Dahl واختار موديل زي MiniMaxAI/MiniMax-M2.7."
            )
            return
        }

        val imageDataUrl = imageB64?.let { "data:$mediaType;base64,$it" }

        // ── NEW: Build smart context from past conversation summaries ──
        val currentHistoryMessages = messageHistory.map { h ->
            ChatMessage(text = h.text, isUser = h.role == "user")
        }
        val smartContext = ChatSummaryManager.buildSmartContext(
            currentMessages = currentHistoryMessages,
            userQuestion = userText,
            maxSummaries = 3
        )

        // Add the smart context as a system augmentation (not in history)
        val contextAugmentation = if (smartContext.isNotBlank()) {
            "\n\n--- CONTEXT FROM PAST CONVERSATIONS ---\n$smartContext\n"
        } else ""

        val learnedMemoryBlock = LearningMemoryManager.getBlock()

        messageHistory.add(HistoryMessage("user", userText, imageDataUrl))
        trimHistory()

        currentJob?.cancel()
        currentJob = scope.launch {
            try {
                val baseSystemPrompt = TOOL_MODE_SYSTEM_PROMPT + (SystemPromptManager.getContextBlock() ?: "") + learnedMemoryBlock
                val systemPrompt = if (contextAugmentation.isNotBlank()) {
                    baseSystemPrompt + contextAugmentation
                } else {
                    baseSystemPrompt
                }

                // ── NEW: Record prompts in ServiceStatsTracker ──
                ServiceStatsTracker.recordPrompts(
                    systemPrompt = systemPrompt,
                    userPrompt = messageHistory.joinToString("\n") { "${if (it.role == "user") "User" else "Assistant"}: ${it.text}" }
                )

                val messagesArray = buildMessagesArray(systemPrompt)

                val allTextBuilder = StringBuilder()
                val allToolCalls = mutableListOf<ToolCallInfo>()

                // ── Turn 1 ──
                var currentResult = streamDahlRequest(messagesArray, key, onChunk)
                if (currentResult.error != null) {
                    withContext(Dispatchers.Main) { onError(currentResult.error) }
                    return@launch
                }

                allTextBuilder.append(currentResult.text.toString().trim())
                allToolCalls.addAll(currentResult.toolCalls)

                var searchRounds = 0
                val MAX_SEARCH_ROUNDS = 3

                while (searchRounds < MAX_SEARCH_ROUNDS) {
                    val knowledgeSearchCalls = currentResult.toolCalls.filter { it.name == "knowledge_search" }
                    if (knowledgeSearchCalls.isEmpty()) break

                    val allCallsFromCurrentTurn = currentResult.toolCalls
                    messagesArray.put(JSONObject().apply {
                        put("role", "assistant")
                        if (currentResult.text.isNotEmpty()) {
                            put("content", currentResult.text.toString())
                        } else {
                            put("content", JSONObject.NULL)
                        }
                        put("tool_calls", JSONArray().apply {
                            for (tc in allCallsFromCurrentTurn) {
                                put(JSONObject().apply {
                                    put("id", tc.id)
                                    put("type", "function")
                                    put("function", JSONObject().apply {
                                        put("name", tc.name)
                                        put("arguments", tc.arguments)
                                    })
                                })
                            }
                        })
                    })

                    for (tc in allCallsFromCurrentTurn) {
                        val toolContent = when (tc.name) {
                            "knowledge_search" -> {
                                val query = try {
                                    JSONObject(tc.arguments).optString("query", "")
                                } catch (e: Exception) { "" }
                                Log.d(TAG, "Executing knowledge_search: $query")
                                KnowledgeSearchUtil.search(query)
                            }
                            "device_action" -> "Action has been dispatched to the device for execution."
                            else -> "Tool executed."
                        }

                        messagesArray.put(JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", tc.id)
                            put("content", toolContent)
                        })
                    }

                    searchRounds++
                    Log.d(TAG, "Sending follow-up request (round $searchRounds)")
                    currentResult = streamDahlRequest(messagesArray, key, onChunk)
                    if (currentResult.error != null) {
                        withContext(Dispatchers.Main) { onError(currentResult.error) }
                        return@launch
                    }

                    allTextBuilder.append("\n\n").append(currentResult.text.toString().trim())
                    allToolCalls.addAll(currentResult.toolCalls)
                }

                // ── Process ALL device_action calls ──
                val collectedActions = mutableListOf<JSONObject>()
                for (tc in allToolCalls.filter { it.name == "device_action" }) {
                    try {
                        val args = JSONObject(tc.arguments)
                        val action = args.optString("action", "")
                        if (action.isNotBlank() && action in KNOWN_ACTIONS) {
                            collectedActions.add(JSONObject().apply {
                                put("action", action)
                                put("params", try {
                                    JSONObject(args.optString("params", "{}"))
                                } catch (e: Exception) {
                                    JSONObject()
                                })
                            })
                        } else {
                            Log.w(TAG, "Ignoring unknown/hallucinated action: '$action'")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Bad tool-call arguments JSON: ${tc.arguments}")
                    }
                }

                // ── Build final text (strip <think> tags) ──
                val rawText = allTextBuilder.toString().trim()
                val cleanText = rawText.replace(
                    Regex("<think>.*?</think>\\s*", RegexOption.DOT_MATCHES_ALL),
                    ""
                ).trim()

                val assistantText = cleanText
                var hasContent = assistantText.isNotBlank()

                if (assistantText.isNotEmpty()) {
                    messageHistory.add(HistoryMessage("assistant", assistantText))
                    trimHistory()
                }

                if (collectedActions.isNotEmpty()) {
                    val trace = collectedActions.joinToString(", ") { act ->
                        val a = act.optString("action")
                        val p = act.optJSONObject("params")
                        val hint = p?.optString("app_name")?.takeIf { it.isNotBlank() }
                            ?: p?.optString("contact_name")?.takeIf { it.isNotBlank() }
                            ?: p?.optString("destination")?.takeIf { it.isNotBlank() }
                            ?: ""
                        if (hint.isNotBlank()) "$a → $hint" else a
                    }
                    messageHistory.add(HistoryMessage("assistant", "[Action executed: $trace]"))
                    trimHistory()
                }

                withContext(Dispatchers.Main) {
                    if (collectedActions.isNotEmpty()) onAction(collectedActions)
                    if (hasContent || collectedActions.isNotEmpty()) onDone()
                    else if (searchRounds > 0) onError("I searched the knowledge base but couldn't find a complete answer. Please try rephrasing.")
                    else onError("No response from Dahl")
                }

            } catch (e: CancellationException) {
                Log.d(TAG, "Stream cancelled")
                withContext(Dispatchers.Main) { onDone() }
            } catch (e: Exception) {
                Log.e(TAG, "Dahl stream error", e)
                withContext(Dispatchers.Main) { onError("Dahl error: ${e.message}") }
            }
        }
    }

    override fun disconnect() {
        currentJob?.cancel()
        currentJob = null
        _isReady = false
        messageHistory.clear()
        Log.d(TAG, "Dahl provider disconnected")
    }

    fun clearHistory() {
        messageHistory.clear()
        Log.d(TAG, "Conversation history cleared")
    }

    fun setApiKey(key: String) {
        prefs.edit().putString(PREF_DAHL_API_KEY, key.trim()).apply()
        _isReady = key.isNotBlank()
    }

    fun setModel(model: String) {
        prefs.edit().putString(PREF_DAHL_MODEL, model).apply()
    }

    fun clearApiKey() {
        prefs.edit().remove(PREF_DAHL_API_KEY).apply()
        _isReady = false
    }

    private fun trimHistory() {
        while (messageHistory.size > maxHistoryTurns) {
            messageHistory.removeAt(0)
        }
    }
}