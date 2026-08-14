package com.example.app_abdelbaset

import android.content.Context
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LocalVoiceSession(
    private val context: Context,
    private val llmProvider: LlmProvider,
    private val onStateChanged: (State) -> Unit,
    private val onPartialTranscript: (String) -> Unit = {},
    private val onFinalTranscript: (String) -> Unit = {},
    private val onLlmResponse: (String) -> Unit = {},
    private val onProgress: (String) -> Unit = {},
    private val onError: (String) -> Unit = {},
    private val prewarmedTts: TtsEngine? = null,
) {
    enum class State { IDLE, STREAMING_STT, LLM_THINKING, TTS_PLAYING, ERROR }

    companion object {
        private const val TAG = "LocalVoiceSession"
    }

    private var currentState = State.IDLE
        set(value) {
            if (field != value) {
                field = value
                Log.d(TAG, "State -> $value")
                updateVisualizer(value)
                scope.launch { onStateChanged(value) }
            }
        }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val visualizer = VisualizerState.instance

    private var sttEngine: LocalSttEngine? = null
    private var deepgramEngine: DeepgramSttEngine? = null
    private var useOnlineSTT = false
    private var vadEngine: LocalVadEngine? = null
    private var ttsEngine: TtsEngine? = null
    private var sttPulseJob: Job? = null

    // ── NEW: Conversation Manager ──
    private var conversationManager: ConversationManager? = null

    private val sentenceBuffer = StringBuilder()
    private var isSpeaking = false
    private var idleJob: Job? = null

    private val mobileActionExecutor = MobileActionExecutor(context)
    private val jsonBuffer = StringBuilder()
    private var isCollectingJson = false
    private var jsonExecuted = false

    // ── NEW: Conversation tracking for saving sessions ──
    private val conversationMessages = mutableListOf<ChatMessage>()
    private var currentSessionId: String? = null

    // ── NEW: Server chat sync (ChatRepository) ──
    @Volatile private var currentServerNodeId: String? = null

    // ── NEW: TTS Deduplication ──
    private var lastSpokenText: String? = null
    private var ttsSpeakTime: Long = 0

    // ── NEW: هل اتنطقت جمل أثناء الـ streaming (منع إعادة الرد كامل) ──
    private var streamedAnySentence = false

    // ── NEW: تنظيف خرج الـ LLM قبل النطق (تاج <think> والعلامة *) ──
    private var inThinkBlock = false
    private val thinkTagBuffer = StringBuilder()

    // ── NEW: إغلاق الجلسة بعد نطق الوداع (Goodbye) ──
    private var closeSessionAfterSpeech = false

    // ── مانع "قيامة الجلسة": بعد الإغلاق، أي إشارة متأخرة من الـ LLM/CM
    // (مثلاً LISTENING من صدى الصوت أو سترايم متأخر) متفتحش الجلسة تاني ──
    private var isSessionClosed = false

    private fun sendTextToOrb(text: String) {
        val intent = android.content.Intent("com.example.app_abdelbaset.ORB_TEXT")
        intent.putExtra("text", text)
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }

    private fun clearOrbText() {
        val intent = android.content.Intent("com.example.app_abdelbaset.ORB_TEXT")
        intent.putExtra("text", "")
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }

    fun initialize(
        llmBackend: String = "CPU",
        sttMode: SttMode = SttMode.LOCAL,
        deepgramApiKey: String = "",
        onInitDone: (Boolean) -> Unit,
    ) {
        useOnlineSTT = (sttMode == SttMode.ONLINE)

        scope.launch(Dispatchers.IO) {
            try {
                // 1. Init VAD
                val vad = LocalVadEngine(
                    context = context,
                    onSpeechStart = { conversationManager?.onVadSpeechStart() }, // ← NEW
                    onSpeechEnd = { conversationManager?.onVadSpeechEnd() }       // ← NEW
                )
                val vadOk = vad.initFromAssets()
                vadEngine = vad

                // 2. Init TTS
                val tts = prewarmedTts ?: MainActivity.buildTtsEngine(context) ?: LocalTtsEngine(context)
                val ttsDeferred = if (prewarmedTts == null) async { tts.init() } else null
                ttsEngine = tts
                val ttsOk = ttsDeferred?.await() ?: true

                // 3. Init STT
                var sttOk = true
                if (useOnlineSTT) {
                    deepgramEngine = DeepgramSttEngine(
                        apiKey = deepgramApiKey,
                        language = "en",
                        onPartial = { text -> conversationManager?.onPartialTranscript(text) }, // ← NEW
                        onFinal = { text -> conversationManager?.onFinalTranscript(text) },     // ← NEW
                        onError = { err -> scope.launch { onError("STT: $err") } }
                    )
                } else {
                    val stt = LocalSttEngine(
                        context = context,
                        onPartial = { text -> conversationManager?.onPartialTranscript(text) }, // ← NEW
                        onFinal = { text -> conversationManager?.onFinalTranscript(text) },     // ← NEW
                        onError = { err -> scope.launch { onError("STT: $err") } }
                    )
                    sttOk = stt.initFromAssets()
                    sttEngine = stt
                }

                // 4. Init Conversation Manager
                val cm = ConversationManager(
                    context = context,
                    config = ConversationManager.ConversationConfig(
                        pauseMediumMs = 1500,  // هنستنى 2 ثانية كاملة قبل ما نقرر إنك خلصت
                        pauseLongMs = 1700,    // لو سكت 3 ثواني، أكيد خلصت (ننهي الكلام فوراً)
                        bargeInThresholdMs = 400, // مهلة المقاطعة لما هو بيتكلم
                        echoCancellationEnabled = true
                    ),
                    onStateChange = { convState ->
                        // بعد إغلاق الجلسة نتجاهل أي تغيير حالة متأخر (LISTENING
                        // من صدى أو مقاطعة) عشان الجلسة متفتحش من غير wake word
                        if (!isSessionClosed) {
                            val mapped = when (convState) {
                                ConversationManager.ConversationState.IDLE -> State.IDLE
                                ConversationManager.ConversationState.LISTENING -> State.STREAMING_STT
                                ConversationManager.ConversationState.THINKING -> State.LLM_THINKING
                                ConversationManager.ConversationState.SPEAKING -> State.TTS_PLAYING
                                ConversationManager.ConversationState.BARGE_IN -> State.STREAMING_STT
                            }
                            currentState = mapped
                        }
                    },
                    onUserUtterance = { text ->
                        // Add user message to conversation history
                        conversationMessages.add(ChatMessage(text = text, isUser = true))

                        onFinalTranscript(text)
                        startLlmStreaming(text)
                    },
                    onPartialTranscript = { text -> onPartialTranscript(text) },
                    onBargeIn = {
                        // 1. أوقف الـ TTS فوراً
                        ttsEngine?.stop()
                        isSpeaking = false
                        // ملاحظة: الفلاج closeSessionAfterSpeech مش بيتلغى هنا —
                        // لو المستخدم قاطع رد الوداع، الجلسة هتقفل برضه بعد ما
                        // الرد الجديد يخلص (كلمة الوداع ثابتة لكل الجلسة)

                        // 2. ابدأ الاستماع تاني في نفس الـ session
                        startLocalProcessing()
                    },
                    onError = { err -> onError(err) }
                )
                cm.attachVad(vad)
                conversationManager = cm

                val allOk = vadOk && ttsOk && sttOk
                withContext(Dispatchers.Main) { onInitDone(allOk) }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onInitDone(false)
                    onError("Local init: ${e.message}")
                }
            }
        }
    }


    fun onWakeWordDetected() {
        if (currentState != State.IDLE) return
        Log.d(TAG, "Wake word -> starting local STT + VAD")

        // ← NEW: Generate new session ID when starting a new session
        currentSessionId = "session_${System.currentTimeMillis()}"
        currentServerNodeId = null
        conversationMessages.clear()
        closeSessionAfterSpeech = false // ← جلسة جديدة
        isSessionClosed = false         // ← جلسة جديدة: مفتوحة

        startLocalProcessing()
    }

    fun cancel() {
        // ← NEW: Save session before cancelling
        saveCurrentSession()

        sttPulseJob?.cancel()
//        ttsQueueJob?.cancel()
        if (useOnlineSTT) {
            deepgramEngine?.stop()
            deepgramEngine?.releaseStream()
        } else {
            sttEngine?.stop()
            sttEngine?.releaseStream()
        }
        vadEngine?.stop()
        ttsEngine?.stop()
        transitionToIdle()
    }

    fun release() {
        // ← NEW: Save session before releasing
        saveCurrentSession()

        cancel()
        if (useOnlineSTT) deepgramEngine?.release() else sttEngine?.release()
        vadEngine?.release()
        ttsEngine?.release()
        conversationManager?.release() // ← NEW
        llmProvider.disconnect()
        scope.cancel()
    }

    /**
     * حفظ الجلسة الحالية قبل إنهائها
     */
    private fun saveCurrentSession() {
        if (conversationMessages.isEmpty()) return

        val sessionId = currentSessionId ?: "session_${System.currentTimeMillis()}"
        currentSessionId = sessionId

        ChatSummaryManager.saveSession(conversationMessages, sessionId)
        Log.d(TAG, "Saved session $sessionId with ${conversationMessages.size} messages")

        // ── NEW: Sync to server ChatRepository ──
        syncSessionToServer()

        // Clear for next session
        conversationMessages.clear()
    }

    /**
     * مزامنة الجلسة الحالية مع السيرفر عشان تظهر في الـ ChatRepository / ChatScreen
     */
    private fun syncSessionToServer() {
        val prefs = context.getSharedPreferences("axon_prefs", Context.MODE_PRIVATE)
        val rawEndpoint = prefs.getString("endpoint", MainActivity.PRESET_ENDPOINTS[0])
            ?: MainActivity.PRESET_ENDPOINTS[0]
        val messagesToSync = conversationMessages.toList()

        scope.launch(Dispatchers.IO) {
            try {
                val nodeId = currentServerNodeId
                if (nodeId == null) {
                    // أول مرة → POST جديدة ونتخزن الـ node_id
                    val newId = ChatRepository.saveSession(rawEndpoint, messagesToSync)
                    if (newId.isNotEmpty()) {
                        currentServerNodeId = newId
                        Log.d(TAG, "Session synced to server: node $newId")
                    } else {
                        Log.w(TAG, "Server sync failed (saveSession returned empty)")
                    }
                } else {
                    // جلسة مستمرة → تحديث
                    ChatRepository.updateSession(rawEndpoint, nodeId, messagesToSync)
                    Log.d(TAG, "Session updated on server: node $nodeId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server sync failed: ${e.message}")
            }
        }
    }

    fun isModelReady(): Boolean = llmProvider.isReady

    private fun startLocalProcessing() {
        idleJob?.cancel()
        idleJob = null

        if (useOnlineSTT) {
            deepgramEngine?.reset()
            // ← الربط المفقود: ابعت الصوت للـ VAD
            deepgramEngine?.onAudioFrame = { buf, len -> vadEngine?.processAudio(buf, len) }
            deepgramEngine?.start()
        } else {
            sttEngine?.reset()
            // ← الربط المفقول للـ Local STT
            sttEngine?.onAudioFrame = { buf, len -> vadEngine?.processAudio(buf, len) }
            sttEngine?.start()
        }
        vadEngine?.start()

        // ابدأ الإدارة الذكية للمحادثة
        conversationManager?.startListening()
    }

    private fun onVadSpeechEnd() {
        Log.d(TAG, "VAD speech end -> stopping STT")

        scope.launch(Dispatchers.IO) {
            if (useOnlineSTT) {
                deepgramEngine?.stop()
                withContext(Dispatchers.Main) {
                    sttPulseJob?.cancel()
                    sttPulseJob = null
                    visualizer.setAudioLevel(0f)
                }

                delay(8000)
                if (currentState == State.STREAMING_STT) {
                    Log.w(TAG, "Deepgram timeout, using fallback text")
                    val fallback = deepgramEngine?.getFinalText() ?: ""
                    withContext(Dispatchers.Main) {
                        if (fallback.isNotBlank()) {
                            onFinalTranscript(fallback)
                            startLlmStreaming(fallback)
                        } else {
                            transitionToIdle()
                        }
                    }
                }
                return@launch
            }

            val stt = sttEngine ?: return@launch
            stt.stop()
            withContext(Dispatchers.Main) {
                sttPulseJob?.cancel()
                sttPulseJob = null
                visualizer.setAudioLevel(0f)
            }

            val finalText = stt.getFinalText()
            stt.releaseStream()

            if (finalText.isBlank()) {
                Log.w(TAG, "Empty transcript -> idle")
                withContext(Dispatchers.Main) { transitionToIdle() }
                return@launch
            }

            withContext(Dispatchers.Main) {
                onFinalTranscript(finalText)
                startLlmStreaming(finalText)
            }
        }
    }

    private fun startLlmStreaming(userText: String) {
        if (isSessionClosed) return // ← جلسة مقفولة: متقبلش كلام جديد
        currentState = State.LLM_THINKING
        visualizer.setState(VisualizerState.OrbState.THINKING)
        visualizer.setAudioLevel(0f)

        // ← لو المستخدم قال Goodbye، هقفل الجلسة بعد ما الـ TTS يخلص الرد
        closeSessionAfterSpeech = isGoodbyeCommand(userText)

        // ← NEW: Notify conversation manager that thinking started
        conversationManager?.notifyThinkingStarted()

        jsonBuffer.clear()
        isCollectingJson = false
        jsonExecuted = false
        inThinkBlock = false
        thinkTagBuffer.clear()

        if (!llmProvider.isReady) {
            onError("LLM provider not ready")
            transitionToIdle()
            return
        }

        sentenceBuffer.clear()
        isSpeaking = false
        streamedAnySentence = false

        val fullResponseBuffer = StringBuilder()
        var handledByToolCall = false

        val json = JSONObject().apply {
            put("type", "text")
            put("text", userText)
        }.toString()

        llmProvider.sendMessage(
            json = json,
            onChunk = { chunk ->
                val cleaned = cleanLlmChunkForSpeech(chunk)
                if (cleaned.isNotEmpty()) {
                    fullResponseBuffer.append(cleaned)
                    onLlmResponse(fullResponseBuffer.toString())

                    // Streaming TTS: ابدأ تشمع الجمل وتكلم فوراً
                    processLlmChunkForTts(cleaned)
                }
            },
            onAction = { actions ->
                handledByToolCall = true

                // Add assistant action response to conversation history
                val actionDesc = actions.joinToString(", ") { act ->
                    "${act.optString("action")}: ${act.optJSONObject("params")?.toString() ?: "{}"}"
                }
                conversationMessages.add(ChatMessage(text = "Executing: $actionDesc", isUser = false))

                scope.launch(Dispatchers.Main) {
                    val confirmations = StringBuilder()
                    for (actionJson in actions) {
                        val action = actionJson.optString("action")
                        val params = actionJson.optJSONObject("params")

                        if (action == "desktop_task") {
                            val text = params?.optString("text", "") ?: ""
                            if (text.isNotBlank()) {
                                sendTextToOrb("Forwarding to desktop...")
                                forwardToDesktopAgent(text) { result ->
                                    scope.launch { speakText(result, isLast = true) }
                                }
                            }
                            continue
                        }

                        val actionResult = kotlinx.coroutines.suspendCancellableCoroutine<String> { cont ->
                            mobileActionExecutor.execute(actionJson) { result ->
                                if (cont.isActive) cont.resume(result) {}
                            }
                        }
                        confirmations.append(actionResult.ifBlank { generateConfirmation(action, params) }).append(" ")
                    }
                    val hasDesktopTask = actions.any { it.optString("action") == "desktop_task" }
                    if (!hasDesktopTask && confirmations.isNotBlank()) {
                        val combined = confirmations.toString().trim()
                        sendTextToOrb(combined)
                        speakText(combined, isLast = true)
                    }
                }
            },
            onDone = {
                if (handledByToolCall) return@sendMessage

                val fullResponse = fullResponseBuffer.toString().trim()

                val jsonRegex = """\{"action":.*\}""".toRegex(RegexOption.DOT_MATCHES_ALL)
                val jsonMatch = jsonRegex.find(fullResponse)

                if (jsonMatch != null) {
                    val jsonStr = jsonMatch.value
                    val cleanedJson = cleanJson(jsonStr)
                    val naturalText = fullResponse.substring(0, jsonMatch.range.first).trim()
                    onLlmResponse(naturalText)

                    if (tryExecuteJson(cleanedJson)) {
                        return@sendMessage
                    }
                }

                if (isDesktopRequest(fullResponse) || isDesktopRequest(userText)) {
                    sendTextToOrb("Forwarding to desktop...")
                    forwardToDesktopAgent(userText) { result ->
                        scope.launch { speakText(result, isLast = true) }
                    }
                    return@sendMessage
                }

                // Add assistant response to conversation history
                val remainingTextForHistory = sentenceBuffer.toString().trim()
                val responseText = if (remainingTextForHistory.isNotBlank()) remainingTextForHistory else fullResponse
                if (responseText.isNotBlank()) {
                    conversationMessages.add(ChatMessage(text = responseText, isUser = false))
                }

                // باقي النص اللي لم يتم التحدث به بعد
                val remainingText = sentenceBuffer.toString().trim()
                if (remainingText.isNotBlank()) {
                    sendTextToOrb(remainingText)
                    speakText(remainingText, isLast = true)
                } else if (streamedAnySentence) {
                    // كل الجمل اتنطقت بالفعل أثناء الـ streaming — ميعيدش الكلام
                    sendTextToOrb(fullResponse)
                    val tts = ttsEngine
                    if (tts != null) {
                        tts.markEndOfStream { finishSpeaking() }
                    } else {
                        finishSpeaking()
                    }
                } else if (fullResponse.isNotBlank()) {
                    sendTextToOrb(fullResponse)  // ← NEW
                    speakText(fullResponse, isLast = true)
                } else {
                    // لو مفيش نص، نبلغ الـ Conversation Manager إن الـ LLM خلص
                    if (closeSessionAfterSpeech) {
                        finishSpeaking()
                    } else {
                        conversationManager?.notifySpeakingEnded()
                        transitionToIdle()
                    }
                }
            },
            onError = { err ->
                Log.e(TAG, "LLM error: $err")
                onError("LLM: $err")
                // في حالة الخطأ، نبلغ الـ Conversation Manager إن المحادثة انتهت
                conversationManager?.notifySpeakingEnded()
                transitionToIdle()
            }
        )
    }

    private fun handleJsonDetection(chunk: String): Boolean {
        val trimmed = chunk.trim()

        // Start collecting JSON
        if (trimmed.contains("{") && !isCollectingJson) {
            isCollectingJson = true
            jsonBuffer.clear()
            // ابدأ من أول { في الـ chunk
            val startIdx = trimmed.indexOf('{')
            jsonBuffer.append(trimmed.substring(startIdx))

            // Check if already complete in this chunk
            val bufferStr = cleanJson(jsonBuffer.toString().trim())
            if (bufferStr.endsWith("}") && isValidJson(bufferStr)) {
                return tryExecuteJson(bufferStr)
            }
            return false
        }

        if (isCollectingJson) {
            jsonBuffer.append(chunk)

            // Check if we have complete JSON
            val bufferStr = cleanJson(jsonBuffer.toString().trim())
            if (bufferStr.endsWith("}") && isValidJson(bufferStr)) {
                return tryExecuteJson(bufferStr)
            }
            return false // Still collecting
        }

        return false
    }

    // ── NEW: Clean malformed JSON from LLM ─────────────────────────
    private fun cleanJson(raw: String): String {
        // أزل المسافات الزيادة من جوه الـ keys والـ values
        // مثال: { " action " : " open _app " } -> {"action":"open_app"}
        var result = raw.trim()

        // أزل المسافات بين الـ quotes والنص: " action " -> "action"
        result = result.replace(Regex("\"\\s+([^\"]+?)\\s+\"")) { matchResult ->
            "\"${matchResult.groupValues[1].replace("\\s+".toRegex(), "")}\""
        }

        // أزل المسافات زيادة بين الـ tokens
        result = result.replace(Regex("\\s*:\\s*"), ":")
        result = result.replace(Regex("\\s*,\\s*"), ",")
        result = result.replace(Regex("\\s*\\{\\s*"), "{")
        result = result.replace(Regex("\\s*\\}\\s*"), "}")

        return result
    }

    private fun isValidJson(str: String): Boolean {
        return try {
            val cleaned = cleanJson(str)
            JSONObject(cleaned)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun tryExecuteJson(jsonStr: String): Boolean {
        return try {
            val cleaned = cleanJson(jsonStr)
            Log.d(TAG, "Trying JSON: $cleaned")

            val actionJson = JSONObject(cleaned)
            if (actionJson.has("action")) {
                val action = actionJson.getString("action").trim()
                val params = actionJson.optJSONObject("params")

                if (action == "desktop_task") {
                    val text = params?.optString("text", "") ?: ""
                    if (text.isNotBlank()) {
                        sendTextToOrb("Forwarding to desktop...")  // ← NEW
                        forwardToDesktopAgent(text) { result ->
                            scope.launch { speakText(result, isLast = true) }
                        }
                    }
                    jsonExecuted = true
                    isCollectingJson = false
                    jsonBuffer.clear()
                    sentenceBuffer.clear()
                    return true
                }

                scope.launch(Dispatchers.Main) {
                    mobileActionExecutor.execute(actionJson)
                }

                val confirmation = generateConfirmation(action, params)
                sendTextToOrb(confirmation)  // ← NEW
                speakText(confirmation, isLast = true)

                jsonExecuted = true
                isCollectingJson = false
                jsonBuffer.clear()
                sentenceBuffer.clear()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse failed: ${e.message} | raw: $jsonStr")
            false
        }
    }

    // NEW: Generate confirmation message
    // ═════════════════════════════════════════════════════════════════════
//  LOCALVOICESESSION.KT — generateConfirmation() (MODIFIED)
// ═════════════════════════════════════════════════════════════════════

    private fun generateConfirmation(action: String, params: JSONObject?): String {
        val appName = params?.optString("app_name", "") ?: ""
        val contact = params?.optString("contact_name", "") ?: ""
        val number = params?.optString("number", "") ?: ""
        val label = params?.optString("label", "") ?: ""
        val destination = params?.optString("destination", "") ?: ""
        val title = params?.optString("title", "") ?: ""
        val name = params?.optString("name", "") ?: ""
        val to = params?.optString("to", "") ?: ""
        val query = params?.optString("query", "") ?: ""
        val text = params?.optString("text", "") ?: ""
        // FIX: optBoolean returns Boolean? → use ?: false
        val enable = params?.optBoolean("enable", true) ?: true
        // FIX: optInt returns Int? → use ?: default
        val hour = params?.optInt("hour", 0) ?: 0
        val minute = params?.optInt("minute", 0) ?: 0
        val seconds = params?.optInt("seconds", 60) ?: 60
        val level = params?.optInt("level", 50) ?: 50
        val camera = params?.optString("camera", "back") ?: "back"
        val duration = params?.optInt("duration", 30) ?: 30
        val mode = params?.optString("mode", "driving") ?: "driving"
        val city = params?.optString("city", "") ?: ""
        val targetLanguage = params?.optString("target_language", "ar") ?: "ar"
        val expression = params?.optString("expression", "") ?: ""
        val message = params?.optString("message", "") ?: ""
        val url = params?.optString("url", "") ?: ""
        val content = params?.optString("content", "") ?: ""
        val date = params?.optString("date", "") ?: ""
        val appPackage = params?.optString("app_package", "") ?: ""
        val replyText = params?.optString("reply_text", "") ?: ""
        val language = params?.optString("language", "en-US") ?: "en-US"
        val path = params?.optString("path", "") ?: ""
        val filename = params?.optString("filename", "") ?: ""

        return when (action) {
            "open_app" -> {
                if (appName.isNotBlank()) listOf(
                    "Opening $appName right away!",
                    "Sure thing! Launching $appName now.",
                    "There you go, $appName is opening up.",
                    "Done! $appName should be up in a second.",
                    "On it! Opening $appName for you."
                ).random()
                else "Opening the app!"
            }

            "call" -> when {
                contact.isNotBlank() -> listOf(
                    "Calling $contact now.",
                    "Dialing $contact... one sec!",
                    "Sure, ringing $contact right away.",
                    "Connecting you to $contact."
                ).random()
                number.isNotBlank() -> listOf(
                    "Calling $number now.",
                    "Dialing $number...",
                    "Ringing $number for you."
                ).random()
                else -> "Making the call!"
            }

            "set_alarm" -> {
                val timeStr = String.format("%02d:%02d", hour, minute)
                if (label.isNotBlank()) listOf(
                    "Alarm set for $timeStr — $label.",
                    "Done! Your $label alarm is set for $timeStr.",
                    "Got it. $label alarm at $timeStr."
                ).random()
                else listOf(
                    "Alarm set for $timeStr.",
                    "Done! Alarm at $timeStr.",
                    "Got it, alarm at $timeStr."
                ).random()
            }

            "set_timer" -> {
                val mins = seconds / 60
                val secs = seconds % 60
                val timeStr = if (mins > 0) "$mins min${if (mins > 1) "s" else ""}${
                    if (secs > 0) " $secs sec${if (secs > 1) "s" else ""}" else ""
                }" else "$seconds seconds"
                if (label.isNotBlank()) listOf(
                    "Timer set for $timeStr — $label.",
                    "Done! $label timer for $timeStr.",
                    "Got it. $timeStr timer started."
                ).random()
                else listOf(
                    "Timer set for $timeStr.",
                    "Done! Timer for $timeStr.",
                    "Got it, timer for $timeStr."
                ).random()
            }

            "volume_up" -> listOf(
                "Turning the volume up.",
                "Volume up!",
                "Louder it is!"
            ).random()

            "volume_down" -> listOf(
                "Turning the volume down.",
                "Volume down!",
                "Quieter now."
            ).random()

            "volume_set" -> listOf(
                "Volume set to $level.",
                "Done! Volume at $level.",
                "Got it, volume $level."
            ).random()

            "volume_mute" -> listOf(
                "Muting now.",
                "Muted!",
                "Silence mode on."
            ).random()

            "volume_unmute" -> listOf(
                "Unmuting now.",
                "Sound back on!",
                "Unmuted!"
            ).random()

            "brightness_set" -> listOf(
                "Brightness set to $level.",
                "Done! Screen brightness at $level.",
                "Got it, brightness $level."
            ).random()

            "lock_phone", "screen_lock" -> listOf(
                "Locking your phone now.",
                "Screen locked!",
                "Phone secured."
            ).random()

            "screenshot" -> listOf(
                "Taking a screenshot!",
                "Screenshot captured!",
                "Snap! Got it."
            ).random()

            // FIX: All optBoolean usages now use ?: true instead of direct value
            "wifi_toggle" -> if (enable) listOf(
                "Turning WiFi on.",
                "WiFi enabled!",
                "Connected to wireless."
            ).random() else listOf(
                "Turning WiFi off.",
                "WiFi disabled.",
                "Wireless off."
            ).random()

            "bluetooth_toggle" -> if (enable) listOf(
                "Turning Bluetooth on.",
                "Bluetooth enabled!",
                "Wireless devices ready."
            ).random() else listOf(
                "Turning Bluetooth off.",
                "Bluetooth disabled.",
                "Wireless devices off."
            ).random()

            "airplane_mode" -> if (enable) listOf(
                "Airplane mode on.",
                "All radios off. Airplane mode enabled.",
                "Flying mode activated!"
            ).random() else listOf(
                "Airplane mode off.",
                "Radios back on!",
                "Normal mode restored."
            ).random()

            "do_not_disturb" -> if (enable) listOf(
                "Do not disturb on.",
                "Focus mode activated!",
                "Notifications silenced."
            ).random() else listOf(
                "Do not disturb off.",
                "Notifications back on!",
                "Focus mode off."
            ).random()

            "hotspot_toggle" -> if (enable) listOf(
                "Hotspot enabled!",
                "Sharing internet now.",
                "WiFi hotspot on."
            ).random() else listOf(
                "Hotspot disabled.",
                "Internet sharing off.",
                "WiFi hotspot off."
            ).random()

            "flashlight_toggle" -> if (enable) listOf(
                "Flashlight on!",
                "Let there be light!",
                "Torch activated."
            ).random() else listOf(
                "Flashlight off.",
                "Lights out!",
                "Torch off."
            ).random()

            "send_sms" -> if (number.isNotBlank()) listOf(
                "Message sent to $number.",
                "SMS delivered to $number!",
                "Text sent to $number."
            ).random() else "Message sent!"

            "send_whatsapp" -> if (number.isNotBlank()) listOf(
                "WhatsApp opened for $number.",
                "Sending WhatsApp to $number...",
                "WhatsApp ready for $number."
            ).random() else "WhatsApp opened!"

            "email_send" -> if (to.isNotBlank()) listOf(
                "Email to $to ready!",
                "Drafting email for $to...",
                "Email composer opened for $to."
            ).random() else "Email ready!"

            "play_music" -> listOf(
                "Playing music!",
                "Music started.",
                "Here we go!"
            ).random()

            "pause_music" -> listOf(
                "Music paused.",
                "Paused!",
                "Music stopped."
            ).random()

            "next_track" -> listOf(
                "Next track!",
                "Skipping forward.",
                "Next song!"
            ).random()

            "previous_track" -> listOf(
                "Previous track!",
                "Going back.",
                "Last song!"
            ).random()

            "take_photo" -> listOf(
                "Camera opened for a photo!",
                "Say cheese!",
                "Photo mode ready ($camera camera)."
            ).random()

            "record_video" -> listOf(
                "Recording video for $duration seconds!",
                "Camera rolling ($duration sec, $camera cam)!",
                "Video capture started!"
            ).random()

            "navigate_to" -> if (destination.isNotBlank()) listOf(
                "Navigating to $destination!",
                "Directions to $destination...",
                "Let's go to $destination!"
            ).random() else "Navigation started!"

            "get_location" -> listOf(
                "Getting your location...",
                "Locating you now.",
                "Where are we? Checking..."
            ).random()

            "share_location" -> listOf(
                "Sharing your location!",
                "Location link ready.",
                "Sending location..."
            ).random()

            "battery_status" -> listOf(
                "Checking battery...",
                "Battery info coming up!",
                "Let me check power levels."
            ).random()

            "memory_status" -> listOf(
                "Checking memory...",
                "RAM usage coming up!",
                "Memory stats on the way."
            ).random()

            "calendar_add_event" -> if (title.isNotBlank()) listOf(
                "Event '$title' added!",
                "Calendar updated with '$title'.",
                "Done! '$title' is scheduled."
            ).random() else "Event added!"

            "calendar_view" -> listOf(
                "Opening your calendar...",
                "Calendar coming up!",
                "Here are your events."
            ).random()

            "reminder_set" -> if (text.isNotBlank()) listOf(
                "Reminder set: '$text'.",
                "Got it! Reminder for '$text'.",
                "I'll remind you about '$text'."
            ).random() else "Reminder set!"

            "contact_add" -> if (name.isNotBlank()) listOf(
                "Contact '$name' added!",
                "Saving $name to contacts...",
                "Done! $name is in your contacts."
            ).random() else "Contact added!"

            "contact_search" -> if (name.isNotBlank()) listOf(
                "Searching for '$name'...",
                "Looking up $name...",
                "Finding $name in contacts..."
            ).random() else "Searching contacts..."

            "notes_add" -> if (title.isNotBlank()) listOf(
                "Note '$title' saved!",
                "Saved '$title' to notes.",
                "Got it! '$title' noted."
            ).random() else "Note saved!"

            "copy_to_clipboard" -> if (text.isNotBlank()) listOf(
                "Copied to clipboard!",
                "'$text' copied!",
                "Clipboard updated."
            ).random() else "Copied!"

            "weather_check" -> if (city.isNotBlank()) listOf(
                "Checking weather in $city...",
                "$city forecast coming up!",
                "Weather for $city..."
            ).random() else listOf(
                "Checking local weather...",
                "Weather forecast coming up!",
                "Let me check the sky..."
            ).random()

            "search_web" -> if (query.isNotBlank()) listOf(
                "Searching for '$query'...",
                "Looking up '$query'...",
                "Web search: '$query'"
            ).random() else "Searching..."

            "translate" -> if (text.isNotBlank()) listOf(
                "Translating '$text'...",
                "Converting to $targetLanguage...",
                "Translation ready!"
            ).random() else "Translating..."

            "calculate" -> if (expression.isNotBlank()) listOf(
                "Calculating $expression...",
                "Math time: $expression",
                "Result for $expression..."
            ).random() else "Calculating..."

            "stopwatch_start" -> listOf(
                "Stopwatch started!",
                "Timer running...",
                "Counting up!"
            ).random()

            "stopwatch_stop" -> listOf(
                "Stopwatch stopped!",
                "Timer paused.",
                "Stopped counting."
            ).random()

            "stopwatch_reset" -> listOf(
                "Stopwatch reset!",
                "Back to zero.",
                "Cleared!"
            ).random()

            "open_url" -> if (url.isNotBlank()) listOf(
                "Opening $url...",
                "Launching browser...",
                "Heading to $url!"
            ).random() else "Opening browser..."

            "desktop_task" -> listOf(
                "Forwarding to your desktop...",
                "Sending to laptop...",
                "Desktop agent on it!"
            ).random()

            "dismiss_notification" -> if (appPackage.isNotBlank()) listOf(
                "Dismissing notifications from $appPackage...",
                "Cleared $appPackage alerts.",
                "Notifications dismissed!"
            ).random() else listOf(
                "Dismissing all notifications...",
                "Cleared everything!",
                "Notifications gone!"
            ).random()

            "read_last_notification" -> if (appPackage.isNotBlank()) listOf(
                "Reading last notification from $appPackage...",
                "Latest from $appPackage...",
                "Here's what $appPackage said..."
            ).random() else listOf(
                "Reading your last notification...",
                "Latest alert...",
                "Here's what you missed..."
            ).random()

            "reply_notification" -> if (appPackage.isNotBlank() && replyText.isNotBlank()) listOf(
                "Replying to $appPackage...",
                "Message sent to $appPackage!",
                "Done! Replied to $appPackage."
            ).random() else "Reply sent!"

            "start_voice_recognition" -> listOf(
                "Listening...",
                "I'm all ears!",
                "Voice input ready ($language)."
            ).random()

            "speak_text" -> if (text.isNotBlank()) listOf(
                "Saying: '$text'",
                "Speaking now...",
                "Here goes: '$text'"
            ).random() else "Speaking..."

            "click_ui_element" -> listOf(
                "Tapping that for you...",
                "Element clicked!",
                "Done tapping."
            ).random()

            "scroll_screen" -> listOf(
                "Scrolling...",
                "Moving the screen...",
                "Scrolled!"
            ).random()

            "find_text_on_screen" -> listOf(
                "Searching on screen...",
                "Looking for that...",
                "Found it!"
            ).random()

            "input_text" -> listOf(
                "Typing that in...",
                "Text entered!",
                "Done typing."
            ).random()

            "list_files" -> if (path.isNotBlank()) listOf(
                "Listing files in $path...",
                "Files in $path...",
                "Here's what's in $path:"
            ).random() else listOf(
                "Listing files...",
                "Here are your files:",
                "Directory contents:"
            ).random()

            "search_files" -> if (path.isNotBlank()) listOf(
                "Searching in $path...",
                "Looking in $path...",
                "Search results from $path:"
            ).random() else listOf(
                "Searching files...",
                "Looking for that...",
                "File search results:"
            ).random()

            "open_file" -> if (path.isNotBlank()) listOf(
                "Opening $path...",
                "Launching $path...",
                "Here is $path:"
            ).random() else "Opening file..."

            "delete_file" -> if (path.isNotBlank()) listOf(
                "Deleting $path...",
                "$path removed!",
                "Gone! $path deleted."
            ).random() else "File deleted!"

            "play_audio_file" -> if (path.isNotBlank()) listOf(
                "Playing $path...",
                "Audio started: $path",
                "Now playing $path!"
            ).random() else "Playing audio..."

            "play_video" -> if (path.isNotBlank()) listOf(
                "Playing $path...",
                "Video started: $path",
                "Now playing $path!"
            ).random() else "Playing video..."

            "show_image" -> if (path.isNotBlank()) listOf(
                "Showing $path...",
                "Image opened: $path",
                "Here is $path!"
            ).random() else "Showing image..."

            "open_document" -> if (path.isNotBlank()) listOf(
                "Opening $path...",
                "Document ready: $path",
                "Here is $path!"
            ).random() else "Opening document..."

            "download_file" -> if (url.isNotBlank()) listOf(
                "Downloading from $url...",
                "Download started!",
                "Getting that file for you..."
            ).random() else "Downloading..."

            "set_screen_timeout" -> listOf(
                "Screen timeout updated.",
                "Display timeout set.",
                "Done!"
            ).random()

            "close_app" -> listOf(
                "Closing app...",
                "App closed!",
                "Done!"
            ).random()

            "get_foreground_app" -> listOf(
                "Checking current app...",
                "Here's what's running...",
                "Current app info:"
            ).random()

            "open_whatsapp_chat" -> listOf(
                "Opening WhatsApp...",
                "WhatsApp chat ready!",
                "Launching WhatsApp..."
            ).random()

            "get_network_status" -> listOf(
                "Checking connection...",
                "Network status:",
                "Here's your connection info:"
            ).random()

            "bluetooth_scan" -> listOf(
                "Scanning for devices...",
                "Bluetooth scan started!",
                "Looking for nearby devices..."
            ).random()

            "start_location_tracking" -> listOf(
                "Starting location tracking...",
                "GPS tracking on!",
                "Following your location..."
            ).random()

            "geofence_trigger" -> listOf(
                "Geofence set!",
                "Location alert ready.",
                "Area monitoring started."
            ).random()

            "get_calendar_events" -> if (date.isNotBlank()) listOf(
                "Events for $date...",
                "Calendar for $date:",
                "Here's $date:"
            ).random() else listOf(
                "Today's events...",
                "Calendar coming up!",
                "Here are your events:"
            ).random()

            "unknown" -> listOf(
                "Hmm, not sure about that one.",
                "I didn't catch that action.",
                "Can you rephrase that?"
            ).random()

            else -> listOf(
                "Done!",
                "All set!",
                "Finished!",
                "There you go!"
            ).random()
        }
    }

    // ── Desktop task forwarding ────────────────────────────────
    private val DESKTOP_KEYWORDS = listOf(
        "on my laptop", "on my desktop", "on my pc", "on my computer",
        "على اللابتوب", "على الكمبيوتر", "على الجهاز", "على اللاب",
        "laptop", "desktop pc", "my pc"
    )

    private fun isDesktopRequest(text: String): Boolean {
        val lower = text.lowercase()
        return DESKTOP_KEYWORDS.any { lower.contains(it) }
    }

    private fun forwardToDesktopAgent(text: String, onResult: (String) -> Unit) {
        if (text.isBlank()) return
        Log.d(TAG, "Forwarding to desktop agent: ${text.take(60)}")

        val prefs = context.getSharedPreferences("axon_prefs", Context.MODE_PRIVATE)
        val rawEndpoint = prefs.getString("endpoint", MainActivity.PRESET_ENDPOINTS[0])
            ?: MainActivity.PRESET_ENDPOINTS[0]
        val wsUrl = "wss://$rawEndpoint/mobile/ws/remote/remote_${System.currentTimeMillis()}"

        scope.launch(Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

            val fullResponse = StringBuilder()

            val latch = kotlinx.coroutines.CompletableDeferred<String>()

            client.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    ws.send(JSONObject().apply { put("text", text) }.toString())
                }

                override fun onMessage(ws: WebSocket, message: String) {
                    try {
                        val json = JSONObject(message)
                        when (json.optString("type")) {
                            "sentence" -> {
                                fullResponse.append(json.optString("text", "")).append(" ")
                            }
                            "done" -> {
                                val result = if (fullResponse.isNotEmpty()) fullResponse.toString().trim()
                                else json.optString("text", "")
                                ws.close(1000, "Done")
                                client.dispatcher.executorService.shutdown()
                                latch.complete(result)
                            }
                            "error" -> {
                                latch.complete("Error: ${json.optString("text", "")}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Remote WS parse: ${e.message}")
                    }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "Remote WS failure: ${t.message}")
                    latch.complete("Remote agent error: ${t.message}")
                }
            })

            val result = latch.await()
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }



    // ── الوداع: إغلاق الجلسة ───────────────────────────────────────
    private val GOODBYE_REGEX = Regex("\\b(goodbye|bye)\\b", RegexOption.IGNORE_CASE)

    private fun isGoodbyeCommand(text: String): Boolean =
        GOODBYE_REGEX.containsMatchIn(text)

    /**
     * يُستدعى كل ما نطق الـ TTS يخلص. لو المستخدم قال "Goodbye"،
     * بعد ما الرد يُقال بالكامل نقفل الجلسة (نحفظ المحادثة + نوقف
     * الاستماع + نرجع للـ wake word) بدل ما نكمل المحادثة.
     */
    private fun finishSpeaking() {
        isSpeaking = false
        if (closeSessionAfterSpeech) {
            closeSessionAfterSpeech = false
            Log.d(TAG, "Goodbye said — closing local voice session")
            isSessionClosed = true
            saveCurrentSession()
            conversationManager?.stop()
            transitionToIdle()
        } else if (!isSessionClosed) {
            conversationManager?.notifySpeakingEnded()
        }
    }

    private fun transitionToIdle() {
        if (useOnlineSTT) deepgramEngine?.stop() else sttEngine?.stop()
        vadEngine?.stop()
        isSpeaking = false
        sentenceBuffer.clear()
        jsonBuffer.clear()
        isCollectingJson = false
        jsonExecuted = false
        inThinkBlock = false
        thinkTagBuffer.clear()

        visualizer.setSpeaking(false)
        visualizer.setListening(false)
        visualizer.setAudioLevel(0f)
        clearOrbText()

        scope.launch {
            delay(200)
            visualizer.deactivate()
            currentState = State.IDLE
        }
    }

    // ── تنظيف خرج الـ LLM قبل النطق ─────────────────────────────────
    /**
     * بيمسح محتوى <think>...</think> (حتى لو التاج متقسّم على أكثر من chunk)
     * وبيشيل كل العلامات * من النص، بحيث الـ TTS ميتنطقش أي تفكير داخلي.
     */
    private fun cleanLlmChunkForSpeech(raw: String): String {
        val combined = thinkTagBuffer.toString() + raw
        thinkTagBuffer.clear()
        if (combined.isEmpty()) return ""

        // لو آخر النص فيه بداية تاج ناقصة (مثلاً "<think" أو "</th") نحتفظ بيها
        // ونكملها مع الـ chunk الجاي بدل ما تتنطق
        val (body, pendingTail) = splitPartialTagTail(combined)
        if (pendingTail.isNotEmpty()) thinkTagBuffer.append(pendingTail)

        val out = StringBuilder(body.length)
        var i = 0
        var inside = inThinkBlock
        while (i < body.length) {
            if (inside) {
                val closeIdx = body.indexOf("</think>", i)
                if (closeIdx == -1) break // باقي النص كله جوه التفكير → امسحه
                i = closeIdx + 8
                inside = false
                continue
            }

            val openIdx = body.indexOf("<think>", i)
            val closeIdx = body.indexOf("</think>", i)
            val nextTag = when {
                openIdx == -1 && closeIdx == -1 -> -1
                openIdx == -1 -> closeIdx
                closeIdx == -1 -> openIdx
                else -> minOf(openIdx, closeIdx)
            }
            if (nextTag == -1) {
                out.append(body.substring(i))
                break
            }
            out.append(body.substring(i, nextTag))
            if (openIdx != -1 && (closeIdx == -1 || openIdx <= closeIdx)) {
                // بداية تفكير جديد → نسقط كل حاجة لحد الغلق
                inside = true
                i = nextTag + 7
            } else {
                // تاج غلق شارد من غير فتح → نشيله بس
                i = nextTag + 8
            }
        }
        inThinkBlock = inside

        return out.toString().replace("*", "")
    }

    // بيرجع (body, tail) بحيث tail أطول ذيل في النص يعتبر بداية ناقصة لتاج
    private fun splitPartialTagTail(text: String): Pair<String, String> {
        val openPrefix = "<think>"
        val closePrefix = "</think>"
        val maxLen = closePrefix.length - 1 // 7 → أي ذيل أقصر من التاج الكامل
        for (len in maxLen downTo 1) {
            val tail = text.takeLast(len)
            if ((openPrefix.length > len && openPrefix.startsWith(tail)) ||
                (closePrefix.length > len && closePrefix.startsWith(tail))
            ) {
                return text.dropLast(len) to tail
            }
        }
        return text to ""
    }

    // تنظيف دفاعي لأي نص هيوصل لـ speakText من غير مسار الـ streaming
    private fun cleanForSpeech(text: String): String {
        return text
            .replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
            .replace("<think>", "")
            .replace("</think>", "")
            .replace("*", "")
            .replace(Regex(" {2,}"), " ") // مسافات مكررة من حدود الـ chunks
            .trim()
    }

    private fun processLlmChunkForTts(chunk: String) {
        if (isSessionClosed) return // ← سترايم متأخر بعد الإغلاق: متتكلمش
        sentenceBuffer.append(chunk)
        var text = sentenceBuffer.toString()

        // أول جملة، نبلغ الـ Conversation Manager إننا بدأنا الكلام
        var firstSentenceInThisChunk = false

        while (true) {
            val endIndex = findSentenceEnd(text)
            if (endIndex == -1) break

            val sentence = text.substring(0, endIndex + 1).trim()
            sentenceBuffer.delete(0, endIndex + 1)
            text = sentenceBuffer.toString()

            if (sentence.isNotBlank()) {
                streamedAnySentence = true
                if (!firstSentenceInThisChunk && !isSpeaking) {
                    // دي أول جملة وهنبدأ نتكلم فيها
                    conversationManager?.notifySpeakingStarted()
                    firstSentenceInThisChunk = true
                }
                speakText(sentence, isLast = false)
            }
        }
    }

    private fun findSentenceEnd(text: String): Int {
        val sentenceEnders = charArrayOf('.', '!', '?', '\n')
        var lastEnd = -1

        for (i in text.indices) {
            if (text[i] in sentenceEnders) {
                lastEnd = i
            }
        }
        return lastEnd
    }

    private fun speakText(text: String, isLast: Boolean) {
        if (isSessionClosed) {
            if (isLast) finishSpeaking() // بيكمل مسار الإغلاق لو نده حد
            return
        }
        val cleaned = cleanForSpeech(text)
        if (cleaned.isBlank()) {
            if (isLast) finishSpeaking()
            return
        }

        // ── NEW: Prevent duplicate TTS playback ──
        val currentTime = System.currentTimeMillis()
        if (cleaned == lastSpokenText && (currentTime - ttsSpeakTime) < 3000) {
            Log.d(TAG, "TTS: Skipping duplicate text")
            if (isLast) finishSpeaking()
            return
        }
        lastSpokenText = cleaned
        ttsSpeakTime = currentTime

        val tts = ttsEngine ?: run {
            Log.w(TAG, "TTS engine not available")
            return
        }

        // ← NEW: Check TTS state before speaking to avoid "not bound" errors
        if (!isSpeaking) {
            isSpeaking = true
            // تم نقل notifySpeakingStarted إلى processLlmChunkForTts عشان يندى قبل أول جملة
            try {
                tts.speak(cleaned, isLast) {
                    if (isLast) finishSpeaking()
                }
            } catch (e: Exception) {
                Log.e(TAG, "TTS speak failed: ${e.message}")
                // حتى لو الـ TTS فشل، نكمل مسار انتهاء الكلام
                if (isLast) finishSpeaking()
            }
        } else {
            try {
                tts.queueSentence(cleaned, isLast) {
                    if (isLast) finishSpeaking()
                }
            } catch (e: Exception) {
                Log.e(TAG, "TTS queue failed: ${e.message}")
                if (isLast) finishSpeaking()
            }
        }
    }


    private fun updateVisualizer(state: State) {
        visualizer.setAudioLevel(0f)
        sttPulseJob?.cancel()
        sttPulseJob = null

        when (state) {
            State.IDLE -> {
                visualizer.setSpeaking(false)
                visualizer.setListening(false)
                visualizer.deactivate()
            }
            State.STREAMING_STT -> {
                visualizer.activate()
                visualizer.setListening(true)
                visualizer.setSpeaking(false)
                sttPulseJob = scope.launch {
                    while (isActive) {
                        visualizer.setAudioLevel(0.35f)
                        delay(120)
                        visualizer.setAudioLevel(0.15f)
                        delay(120)
                    }
                }
            }
            State.LLM_THINKING -> {
                visualizer.setState(VisualizerState.OrbState.THINKING)
                visualizer.setAudioLevel(0f)
            }
            State.TTS_PLAYING -> {
                visualizer.setSpeaking(true)
            }
            State.ERROR -> {
                visualizer.setSpeaking(false)
                visualizer.setListening(false)
                visualizer.deactivate()
            }
        }
    }
}