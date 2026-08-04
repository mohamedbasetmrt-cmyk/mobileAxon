package com.example.app_abdelbaset

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import androidx.compose.foundation.layout.imePadding
import android.Manifest
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Mic
import androidx.compose.foundation.shape.CircleShape
import com.example.app_abdelbaset.ui.theme.*
import com.example.app_abdelbaset.ui.theme.BgPrimary
import com.example.app_abdelbaset.ui.theme.BgSecondary
import com.example.app_abdelbaset.ui.theme.CardBg
import com.example.app_abdelbaset.ui.theme.CardBorder
import com.example.app_abdelbaset.ui.theme.TextPrimary
import com.example.app_abdelbaset.ui.theme.TextMuted
import com.example.app_abdelbaset.ui.theme.NeonGreen
import com.example.app_abdelbaset.ui.theme.NeonCyan
import com.example.app_abdelbaset.ui.theme.AccentPink
import com.example.app_abdelbaset.ui.theme.AppFontFamily
import kotlinx.coroutines.withContext
import android.util.Log
import kotlinx.coroutines.delay
import org.json.JSONArray
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.combinedClickable
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.core.content.ContextCompat

data class ChatMessage(
    val text:     String,
    val isUser:   Boolean,
    val isTyping: Boolean = false,
    val image:    android.graphics.Bitmap? = null
)

// enum لمساعدة واجهة المايك
//enum class VoiceState { IDLE, RECORDING, PROCESSING }

// ═══════════════════════════════════════════════════════════════════════
//  MARKDOWN RENDERER
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun InlineMarkdownText(
    text: String,
    fontSize: Float,
    color: Color = TextPrimary,
    fontFamily: FontFamily = AppFontFamily,
    lineHeight: Float = fontSize + 6f
) {
    val annotated = buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                i + 1 < text.length && text[i] == '*' && text[i + 1] == '*' -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i]); i++
                    }
                }
                text[i] == '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end != -1 && end > i + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i]); i++
                    }
                }
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end != -1) {
                        withStyle(SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            color = NeonGreen,
                            background = CardBg
                        )) {
                            append(" " + text.substring(i + 1, end) + " ")
                        }
                        i = end + 1
                    } else {
                        append(text[i]); i++
                    }
                }
                text[i] == '[' -> {
                    val textEnd = text.indexOf(']', i + 1)
                    if (textEnd != -1 && textEnd + 1 < text.length && text[textEnd + 1] == '(') {
                        val urlEnd = text.indexOf(')', textEnd + 2)
                        if (urlEnd != -1) {
                            val linkText = text.substring(i + 1, textEnd)
                            withStyle(SpanStyle(color = NeonCyan, fontWeight = FontWeight.Medium)) {
                                append(linkText)
                            }
                            i = urlEnd + 1
                        } else {
                            append(text[i]); i++
                        }
                    } else {
                        append(text[i]); i++
                    }
                }
                else -> {
                    append(text[i]); i++
                }
            }
        }
    }

    Text(
        text       = annotated,
        fontSize   = fontSize.sp,
        color      = color,
        fontFamily = fontFamily,
        lineHeight = lineHeight.sp
    )
}

@Composable
private fun MarkdownText(
    text: String,
    fontSize: Float,
    color: Color = TextPrimary,
    fontFamily: FontFamily = AppFontFamily
) {
    if (text.isBlank()) return

    val lines = text.split("\n")
    Column(
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        var inCodeBlock = false
        val codeBuffer  = StringBuilder()

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // ── Code block toggle ──
            if (trimmed.startsWith("```")) {
                if (inCodeBlock) {
                    if (codeBuffer.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(CardBg.copy(alpha = 0.6f))
                                .border(0.5.dp, NeonGreen.copy(0.3f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text       = codeBuffer.toString().trimEnd(),
                                fontSize   = (fontSize - 1).sp,
                                color      = NeonGreen.copy(0.9f),
                                fontFamily = FontFamily.Monospace,
                                lineHeight = (fontSize + 3).sp
                            )
                        }
                    }
                    codeBuffer.clear()
                    inCodeBlock = false
                } else {
                    inCodeBlock = true
                }
                i++
                continue
            }

            if (inCodeBlock) {
                if (codeBuffer.isNotEmpty()) codeBuffer.append("\n")
                codeBuffer.append(line)
                if (i == lines.lastIndex && codeBuffer.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(CardBg.copy(alpha = 0.6f))
                            .border(0.5.dp, NeonGreen.copy(0.3f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text       = codeBuffer.toString().trimEnd(),
                            fontSize   = (fontSize - 1).sp,
                            color      = NeonGreen.copy(0.9f),
                            fontFamily = FontFamily.Monospace,
                            lineHeight = (fontSize + 3).sp
                        )
                    }
                }
                i++
                continue
            }

            // ── Table Detection ──
            if (trimmed.contains("|") && i + 1 < lines.size && isTableSeparator(lines[i+1].trim())) {
                val headerLine = trimmed
                val dataLines = mutableListOf<String>()
                var j = i + 2
                while (j < lines.size) {
                    val dataLine = lines[j].trim()
                    if (dataLine.contains("|")) {
                        dataLines.add(dataLine)
                        j++
                    } else {
                        break
                    }
                }

                // Render Table
                RenderMarkdownTable(headerLine, dataLines, fontSize, color, fontFamily)

                i = j
                continue
            }

            // ── سطر فاضي ──
            if (line.isBlank()) {
                Spacer(Modifier.height(3.dp))
                i++
                continue
            }

            // ── Horizontal rule ──
            if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
                Divider(
                    color     = CardBorder,
                    thickness = 0.5.dp,
                    modifier  = Modifier.padding(vertical = 4.dp)
                )
                i++
                continue
            }

            // ── Headings: # ## ### ──
            val headingMatch = Regex("^(#{1,6})\\s+(.*)").find(line)
            if (headingMatch != null) {
                val level   = headingMatch.groupValues[1].length
                val content = headingMatch.groupValues[2]
                val headingFontSize = when (level) {
                    1 -> fontSize + 7f
                    2 -> fontSize + 5f
                    3 -> fontSize + 3f
                    4 -> fontSize + 2f
                    5 -> fontSize + 1f
                    else -> fontSize
                }
                val headingColor = when (level) {
                    1 -> NeonCyan
                    2 -> NeonGreen.copy(0.95f)
                    3 -> NeonGreen.copy(0.85f)
                    else -> color
                }
                InlineMarkdownText(
                    text       = content,
                    fontSize   = headingFontSize,
                    color      = headingColor,
                    fontFamily = fontFamily
                )
                if (level <= 2) Spacer(Modifier.height(1.dp))
                i++
                continue
            }

            // ── Bullet list: - / * / + ──
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
                val content = trimmed.substring(2)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text       = "•  ",
                        fontSize   = fontSize.sp,
                        color      = NeonGreen,
                        fontFamily = fontFamily,
                        lineHeight = (fontSize + 6).sp
                    )
                    InlineMarkdownText(
                        text       = content,
                        fontSize   = fontSize,
                        color      = color,
                        fontFamily = fontFamily
                    )
                }
                i++
                continue
            }

            // ── Numbered list: 1. 2. ──
            val numMatch = Regex("^(\\d+)\\.\\s+(.*)").find(trimmed)
            if (numMatch != null) {
                val num     = numMatch.groupValues[1]
                val content = numMatch.groupValues[2]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text       = "$num. ",
                        fontSize   = fontSize.sp,
                        color      = NeonGreen,
                        fontFamily = fontFamily,
                        lineHeight = (fontSize + 6).sp
                    )
                    InlineMarkdownText(
                        text       = content,
                        fontSize   = fontSize,
                        color      = color,
                        fontFamily = fontFamily
                    )
                }
                i++
                continue
            }

            // ── Blockquote: > ──
            if (trimmed.startsWith("> ")) {
                val content = trimmed.substring(2)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(18.dp)
                            .background(NeonCyan.copy(0.5f))
                    )
                    Spacer(Modifier.width(8.dp))
                    InlineMarkdownText(
                        text       = content,
                        fontSize   = fontSize,
                        color      = color.copy(alpha = 0.8f),
                        fontFamily = fontFamily
                    )
                }
                i++
                continue
            }

            // ── سطر عادي ──
            InlineMarkdownText(
                text       = line,
                fontSize   = fontSize,
                color      = color,
                fontFamily = fontFamily
            )
            i++
        }
    }
}

// دالة مساعدة لفحص صف فاصل الجدول
private fun isTableSeparator(line: String): Boolean {
    if (!line.contains("|")) return false
    val cleaned = line.replace("|", "").replace(":", "").replace("-", "").replace(" ", "")
    return cleaned.isEmpty() && line.contains("-")
}

// دالة رسم الجدول نفسه
@Composable
private fun RenderMarkdownTable(
    headerLine: String,
    dataLines: List<String>,
    fontSize: Float,
    color: Color,
    fontFamily: FontFamily
) {
    fun parseRow(line: String): List<String> {
        val cleaned = line.trim().trim('|')
        return cleaned.split("|").map { it.trim() }
    }

    val headers = parseRow(headerLine)
    val rows = dataLines.map { parseRow(it) }

    val maxCols = maxOf(headers.size, rows.maxOfOrNull { it.size } ?: 0)
    if (maxCols == 0) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(0.5.dp, NeonCyan.copy(0.3f), RoundedCornerShape(6.dp))
    ) {
        // Header Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(NeonCyan.copy(alpha = 0.1f))
                .padding(vertical = 6.dp, horizontal = 8.dp)
        ) {
            for (col in 0 until maxCols) {
                val text = headers.getOrElse(col) { "" }
                Text(
                    text = text,
                    fontSize = (fontSize - 1).sp,
                    color = NeonCyan,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Data Rows
        rows.forEachIndexed { index, row ->
            if (index > 0) {
                Divider(color = CardBorder, thickness = 0.5.dp)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardBg.copy(alpha = 0.4f))
                    .padding(vertical = 6.dp, horizontal = 8.dp)
            ) {
                for (col in 0 until maxCols) {
                    val text = row.getOrElse(col) { "" }
                    Text(
                        text = text,
                        fontSize = (fontSize - 1).sp,
                        color = color,
                        fontFamily = fontFamily,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}


@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenWidget: () -> Unit = {},
    onOpenPair: () -> Unit = {}
){

    val context     = LocalContext.current
    val prefs       = context.getSharedPreferences("axon_prefs", android.content.Context.MODE_PRIVATE)
    val rawEndpoint = prefs.getString("endpoint", MainActivity.PRESET_ENDPOINTS[0])
        ?: MainActivity.PRESET_ENDPOINTS[0]

    val messages         = remember { mutableStateListOf<ChatMessage>() }
    var inputText        by remember { mutableStateOf("") }
    var isConnected      by remember { mutableStateOf(false) }
    var isWaiting        by remember { mutableStateOf(false) }
    var showHistory      by remember { mutableStateOf(false) }
    var voiceState       by remember { mutableStateOf(VoiceState.IDLE) }
    var pendingImage     by remember { mutableStateOf<CapturedImage?>(null) }
    var currentSessionId by remember { mutableStateOf("") }
    var llmMode          by remember { mutableStateOf(
        LlmMode.valueOf(prefs.getString("llm_mode", "SERVER") ?: "SERVER")
    ) }
    var localModelState  by remember { mutableStateOf(LocalModelState.UNLOADED) }
    var showSettings     by remember { mutableStateOf(false) }
    var contextSections  by remember { mutableStateOf(ContextSectionStore.load(context)) }

    var messageFontSize  by remember {
        mutableStateOf(prefs.getFloat("message_font_size", 13f))
    }

    // ═════════════════════════════════════════════════════════════════
    //  DEEPGRAM STT ENGINE SETUP
    // ═════════════════════════════════════════════════════════════════
    var deepgramApiKey by remember { mutableStateOf(prefs.getString("deepgram_api_key", "") ?: "") }

    // متغير جديد بيجمع الكلام اللي اتقال عشان ميمسحش القديم
    var accumulatedText by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    val deepgramEngine = remember(deepgramApiKey) {
        DeepgramSttEngine(
            apiKey = deepgramApiKey,
            language = "en",
            onPartial = { partialText ->
                inputText = if (accumulatedText.isBlank()) partialText else "$accumulatedText $partialText"
            },
            onFinal = { finalText ->
                accumulatedText = if (accumulatedText.isBlank()) finalText else "$accumulatedText $finalText"
                inputText = accumulatedText
            },
            onError = { err ->
                messages.add(ChatMessage("🎙️ $err", isUser = false))
                voiceState = VoiceState.IDLE
            }
        )
    }

    fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    fun toggleMic() {
        if (deepgramApiKey.isBlank()) {
            messages.add(ChatMessage("❌ Deepgram API key not set in settings", isUser = false))
            showSettings = true
            return
        }
        when (voiceState) {
            VoiceState.IDLE -> {
                voiceState = VoiceState.RECORDING
                // الدمج هنا: لو فيه كلام مكتوب قبل كده بنخزنه عشان نرجعه ونكمل عليه
                accumulatedText = inputText.trim()
                if (accumulatedText.isNotEmpty()) accumulatedText += " "
                inputText = accumulatedText
                deepgramEngine.reset()
                deepgramEngine.start()
            }
            VoiceState.RECORDING -> {
                voiceState = VoiceState.PROCESSING
                deepgramEngine.stop()
                scope.launch {
                    delay(1000) // وقت لاستقبال أي كلمات أخيرة من Deepgram
                    if (voiceState == VoiceState.PROCESSING) {
                        val text = deepgramEngine.getFinalText().trim()
                        if (text.isNotBlank()) {
                            // عشان نتجنب تكرار الجملة لو الـ onFinal سجلها بالفعل
                            if (!accumulatedText.trim().endsWith(text)) {
                                accumulatedText = if (accumulatedText.isBlank()) text else "${accumulatedText.trim()} $text"
                            }
                        }
                        inputText = accumulatedText.trim()
                        voiceState = VoiceState.IDLE
                    }
                }
            }
            else -> {}
        }
    }

    fun refreshSections() { contextSections = ContextSectionStore.load(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshSections()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    var selectedBackend  by remember { mutableStateOf(
        prefs.getString("llm_backend", "CPU") ?: "CPU"
    ) }
    var localLlmProvider by remember { mutableStateOf(
        LocalLlmProviderType.valueOf(prefs.getString("local_llm_provider", "GEMMA_4B") ?: "GEMMA_4B")
    ) }
    var dahlApiKey by remember { mutableStateOf(prefs.getString("dahl_api_key", "") ?: "") }
    var dahlModel by remember { mutableStateOf(prefs.getString("dahl_model", "MiniMaxAI/MiniMax-M2.7") ?: "MiniMaxAI/MiniMax-M2.7") }

    val localProvider = remember {
        LocalLiteRTLMProvider(context).also {
            it.onModelStateChange = { state -> localModelState = state }
        }
    }

    val cohereProvider = remember {
        CohereLlmProvider(context)
    }

    val dahlProvider = remember {
        DahlLlmProvider(context)
    }

    val serverProvider = remember {
        ServerLlmProvider(
            endpoint       = rawEndpoint,
            onConnected    = { isConnected = true },
            onDisconnected = { isConnected = false }
        )
    }

    val activeProvider by remember(llmMode, localLlmProvider) {
        derivedStateOf {
            if (llmMode == LlmMode.LOCAL) {
                when (localLlmProvider) {
                    LocalLlmProviderType.GEMMA_4B -> localProvider as LlmProvider
                    LocalLlmProviderType.COHERE_API -> cohereProvider as LlmProvider
                    LocalLlmProviderType.DAHL_API -> dahlProvider as LlmProvider
                }
            } else {
                serverProvider as LlmProvider
            }
        }
    }

    val listState = rememberLazyListState()
    // تم إزالة val scope = rememberCoroutineScope() من هنا لأنه تم نقله للأعلى

    val client = remember {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val cameraManager = rememberCameraInputManager(
        onImageCaptured = { captured -> pendingImage = captured },
        onError         = { err -> messages.add(ChatMessage("📷 $err", isUser = false)) }
    )

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) toggleMic()
        else messages.add(ChatMessage("⚠️ Microphone permission denied", isUser = false))
    }

    fun saveCurrentSession() {
        val cleanMessages = messages.filter { !it.isTyping }
        if (cleanMessages.isEmpty()) return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (currentSessionId.isEmpty()) {
                val newId = ChatRepository.saveSession(rawEndpoint, cleanMessages)
                if (newId.isNotEmpty()) currentSessionId = newId
            } else {
                ChatRepository.updateSession(rawEndpoint, currentSessionId, cleanMessages)
            }
        }
    }

    fun initProvider() {
        when (llmMode) {
            LlmMode.SERVER -> serverProvider.connect()
            LlmMode.LOCAL  -> {
                isConnected = false
                when (localLlmProvider) {
                    LocalLlmProviderType.GEMMA_4B -> {
                        if (localModelState == LocalModelState.UNLOADED) {
                            localProvider.loadModel(
                                backend   = selectedBackend,
                                onSuccess = { isConnected = true },
                                onError   = { err -> messages.add(ChatMessage("❌ $err", isUser = false)) }
                            )
                        }
                    }
                    LocalLlmProviderType.COHERE_API -> {
                        if (cohereProvider.hasApiKey()) {
                            cohereProvider.connect { isConnected = true }
                        } else {
                            messages.add(ChatMessage("❌ Cohere API key not set", isUser = false))
                        }
                    }
                    LocalLlmProviderType.DAHL_API -> {
                        if (dahlProvider.hasApiKey()) {
                            dahlProvider.connect { isConnected = true }
                        } else {
                            messages.add(ChatMessage("❌ Dahl API key not set", isUser = false))
                        }
                    }
                }
            }
        }
    }

    fun forwardToDesktopAgent(text: String, onResult: (String) -> Unit) {
        if (text.isBlank()) return
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val wsUrl = "wss://$rawEndpoint/mobile/ws/remote/remote_${System.currentTimeMillis()}"
            val wsClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

            val fullResponse = StringBuilder()
            val result = kotlinx.coroutines.CompletableDeferred<String>()

            wsClient.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    ws.send(JSONObject().apply { put("text", text) }.toString())
                }
                override fun onMessage(ws: WebSocket, message: String) {
                    try {
                        val json = JSONObject(message)
                        when (json.optString("type")) {
                            "sentence" -> fullResponse.append(json.optString("text", "")).append(" ")
                            "done" -> {
                                ws.close(1000, "Done")
                                wsClient.dispatcher.executorService.shutdown()
                                if (fullResponse.isNotEmpty()) result.complete(fullResponse.toString().trim())
                                else result.complete(json.optString("text", ""))
                            }
                            "error" -> result.complete("Error: ${json.optString("text", "")}")
                        }
                    } catch (_: Exception) {}
                }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    result.complete("Desktop agent error: ${t.message}")
                }
            })

            val response = result.await()
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                onResult(response)
            }
        }
    }

    fun isDesktopRequest(text: String): Boolean {
        val keywords = listOf(
            "on my laptop", "on my desktop", "on my pc", "on my computer",
            "على اللابتوب", "على الكمبيوتر", "على الجهاز", "على اللاب",
            "laptop", "desktop pc", "my pc"
        )
        val lower = text.lowercase()
        return keywords.any { lower.contains(it) }
    }

    val intentActions = setOf(
        "open_app", "navigate_to", "take_photo", "record_video",
        "call", "send_sms", "send_whatsapp", "email_send",
        "calendar_add_event", "contact_add", "contact_search",
        "calendar_view", "share_location", "get_location",
        "weather_check", "search_web", "translate", "open_url"
    )

    suspend fun executeActionsAndAppendSummary(actions: List<JSONObject>, baseText: String) {
        val executor = MobileActionExecutor(context)
        val resultsLog = StringBuilder()

        for (actionJson in actions) {
            val action = actionJson.optString("action")

            if (action == "desktop_task") {
                val forwardText = actionJson.optJSONObject("params")?.optString("text", "") ?: ""
                if (forwardText.isNotBlank()) {
                    resultsLog.append("🖥️ Forwarding to desktop: ").append(forwardText.take(40)).append("...\n")
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        val curMsg = messages.lastOrNull() ?: return@withContext
                        messages[messages.size - 1] = curMsg.copy(
                            text = curMsg.text + "\n\n🖥️ Forwarding to desktop agent..."
                        )
                    }
                    forwardToDesktopAgent(forwardText) { result ->
                        resultsLog.append("Desktop: ").append(result.take(60)).append("\n")
                    }
                    delay(300)
                    continue
                }
            }

            try {
                val resultText = kotlinx.coroutines.suspendCancellableCoroutine<String> { cont ->
                    executor.execute(actionJson) { result ->
                        if (cont.isActive) cont.resume(result) {}
                    }
                }
                if (resultText.isNotBlank()) resultsLog.append(resultText).append("\n")
            } catch (e: Exception) {
                Log.e("ChatScreen", "Action '$action' failed: ${e.message}")
                resultsLog.append("❌ ").append(action).append(" failed\n")
            }

            if (action in intentActions) delay(300) else delay(150)
        }

        val summary = resultsLog.toString().trim()
        if (summary.isNotEmpty()) {
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                val curMsg = messages.lastOrNull() ?: return@withContext
                messages[messages.size - 1] = curMsg.copy(text = "$baseText\n\n$summary")
            }
        }
    }

    fun extractActions(responseText: String): List<JSONObject> {
        val results = mutableListOf<JSONObject>()

        val lastBracket = responseText.lastIndexOf(']')
        if (lastBracket != -1) {
            val firstBracket = responseText.lastIndexOf('[', lastBracket)
            if (firstBracket != -1 && firstBracket < lastBracket) {
                try {
                    val arr = org.json.JSONArray(responseText.substring(firstBracket, lastBracket + 1))
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i)
                        if (item != null && item.has("action")) {
                            results.add(item)
                        }
                    }
                    if (results.isNotEmpty()) return results
                } catch (_: Exception) {}
            }
        }

        var braceDepth = 0
        var objStart = -1
        var inString = false
        var escape = false

        for (i in responseText.indices) {
            val c = responseText[i]

            if (escape) {
                escape = false
                continue
            }
            if (c == '\\') {
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (inString) continue

            when (c) {
                '{' -> {
                    if (braceDepth == 0) objStart = i
                    braceDepth++
                }
                '}' -> {
                    braceDepth--
                    if (braceDepth == 0 && objStart != -1) {
                        try {
                            val obj = org.json.JSONObject(responseText.substring(objStart, i + 1))
                            if (obj.has("action")) {
                                results.add(obj)
                            }
                        } catch (_: Exception) {}
                        objStart = -1
                    }
                }
            }
        }

        return results
    }

    fun handleActionResponse(
        responseText: String,
        userText: String,
        onResultText: (String) -> Unit
    ): Boolean {
        val actions = extractActions(responseText)

        val jsonStart = if (actions.isNotEmpty()) {
            val firstObj = responseText.indexOf('{')
            val firstArr = responseText.indexOf('[')
            when {
                firstObj == -1 && firstArr == -1 -> responseText.length
                firstObj == -1 -> firstArr
                firstArr == -1 -> firstObj
                else -> minOf(firstObj, firstArr)
            }
        } else -1

        val naturalText = if (jsonStart > 0) responseText.substring(0, jsonStart).trim() else ""

        if (actions.isEmpty()) {
            if (isDesktopRequest(responseText) || isDesktopRequest(userText)) {
                onResultText("🖥️ Forwarding to desktop agent...")
                forwardToDesktopAgent(userText) { result -> onResultText(result) }
                return true
            }
            return false
        }

        if (naturalText.isNotBlank()) {
            onResultText(naturalText)
        } else {
            onResultText(generateMultiConfirmation(actions))
        }

        scope.launch {
            executeActionsAndAppendSummary(actions, naturalText)
        }

        return true
    }

    fun sendMessage() {
        val text = inputText.trim()
        if (text.isEmpty() && pendingImage == null || isWaiting) return
        val capturedImage = pendingImage
        messages.add(ChatMessage(
            text   = if (text.isEmpty()) "📷 Image sent" else text,
            isUser = true,
            image  = capturedImage?.bitmap
        ))
        inputText    = ""
        pendingImage = null
        isWaiting    = true
        messages.add(ChatMessage("...", isUser = false, isTyping = true))
        scope.launch { listState.animateScrollToItem(messages.size - 1) }

        val json = if (capturedImage != null) {
            JSONObject().apply {
                put("type",       if (text.isNotEmpty()) "image_text" else "image")
                put("text",       text)
                put("image",      capturedImage.base64)
                put("media_type", "image/jpeg")
            }.toString()
        } else {
            JSONObject().apply {
                put("type", "text")
                put("text", text)
            }.toString()
        }

        var handledByToolCall = false

        activeProvider.sendMessage(
            json    = json,
            onChunk = { chunk ->
                messages.removeAll { it.isTyping }
                val lastMsg = messages.lastOrNull()
                if (lastMsg != null && !lastMsg.isUser) {
                    messages[messages.size - 1] = lastMsg.copy(text = lastMsg.text + chunk)
                } else {
                    messages.add(ChatMessage(chunk, isUser = false))
                }
                messages.add(ChatMessage("...", isUser = false, isTyping = true))
                scope.launch { listState.animateScrollToItem(messages.size - 1) }
            },
            onAction = { actions ->
                handledByToolCall = true
                messages.removeAll { it.isTyping }

                val lastMsg = messages.lastOrNull()
                val naturalText = if (lastMsg != null && !lastMsg.isUser) lastMsg.text.trim() else ""
                val confirmation = generateMultiConfirmation(actions)
                val displayText = if (naturalText.isNotBlank()) "$naturalText\n\n$confirmation" else confirmation

                if (lastMsg != null && !lastMsg.isUser) {
                    messages[messages.size - 1] = lastMsg.copy(text = displayText)
                } else {
                    messages.add(ChatMessage(displayText, isUser = false))
                }

                scope.launch {
                    executeActionsAndAppendSummary(actions, displayText)
                }
            },
            onDone = {
                messages.removeAll { it.isTyping }
                if (!handledByToolCall) {
                    val lastMsg = messages.lastOrNull()
                    if (lastMsg != null && !lastMsg.isUser) {
                        val rawResponse = lastMsg.text
                        val cleanResponse = rawResponse.replace(
                            Regex("折扣.*? Discounts", RegexOption.DOT_MATCHES_ALL),
                            ""
                        ).trim()

                        val handled = handleActionResponse(
                            responseText = cleanResponse,
                            userText     = text,
                            onResultText = { resultText ->
                                messages[messages.size - 1] = lastMsg.copy(text = resultText)
                            }
                        )

                        if (!handled) {
                            messages[messages.size - 1] = lastMsg.copy(text = cleanResponse)
                        }
                    }
                }
                isWaiting = false
                saveCurrentSession()
                ChatSessionState.save(messages, currentSessionId)
                scope.launch { listState.animateScrollToItem(messages.size - 1) }
            },
            onError = { err ->
                messages.removeAll { it.isTyping }
                messages.add(ChatMessage("❌ $err", isUser = false))
                isWaiting = false
            }
        )
    }

    fun loadSession(session: ChatSession) {
        saveCurrentSession()
        messages.clear()
        messages.addAll(session.messages)
        currentSessionId = session.id
        ChatSessionState.save(messages, currentSessionId)
        showHistory = false
        scope.launch { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }
    }

    fun startNewChat() {
        saveCurrentSession()
        messages.clear()
        currentSessionId = ""
        ChatSessionState.clear()
        cohereProvider.clearHistory()
        dahlProvider.clearHistory()
        showHistory = false
    }

    LaunchedEffect(Unit) {
        if (ChatSessionState.hasSession()) {
            messages.clear()
            messages.addAll(ChatSessionState.messages)
            currentSessionId = ChatSessionState.sessionId
            scope.launch {
                if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
            }
        }
        initProvider()
    }

    DisposableEffect(Unit) {
        onDispose {
            ChatSessionState.save(messages, currentSessionId)
            saveCurrentSession()
            serverProvider.disconnect()
            localProvider.unloadModel()
            cohereProvider.clearHistory()
            dahlProvider.clearHistory()
            deepgramEngine.release()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(BgPrimary)) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgSecondary)
                    .border(0.5.dp, CardBorder, RoundedCornerShape(0.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(CardBg)
                        .border(0.5.dp, CardBorder, RoundedCornerShape(6.dp))
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back",
                        tint = TextPrimary, modifier = Modifier.size(15.dp))
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text          = "AXON CHAT",
                        fontSize      = 14.sp,
                        fontWeight    = FontWeight.Bold,
                        color         = TextPrimary,
                        letterSpacing = 4.sp,
                        fontFamily    = AppFontFamily
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(if (isConnected) NeonGreen else TextMuted)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text          = if (isConnected) "LINK ESTABLISHED" else "CONNECTING...",
                            fontSize      = 8.sp,
                            color         = if (isConnected) NeonGreen.copy(0.8f) else TextMuted,
                            letterSpacing = 1.sp,
                            fontFamily    = AppFontFamily
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(CardBg)
                        .border(0.5.dp, CardBorder, RoundedCornerShape(6.dp))
                        .clickable { startNewChat() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "New Chat",
                        tint = NeonCyan, modifier = Modifier.size(15.dp))
                }

                Spacer(Modifier.width(6.dp))
                Spacer(Modifier.width(6.dp))

                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(CardBg)
                        .border(0.5.dp, CardBorder, RoundedCornerShape(6.dp))
                        .clickable { showSettings = !showSettings },
                    contentAlignment = Alignment.Center
                ) {
                    Text("⚙", fontSize = 14.sp, color = TextMuted)
                }
                Spacer(Modifier.width(6.dp))
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (showHistory) NeonGreen.copy(0.12f) else CardBg)
                        .border(
                            0.5.dp,
                            if (showHistory) NeonGreen.copy(0.5f) else CardBorder,
                            RoundedCornerShape(6.dp)
                        )
                        .clickable { showHistory = !showHistory },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "☰",
                        fontSize = 14.sp,
                        color    = if (showHistory) NeonGreen else TextMuted
                    )
                }
            }

            LazyColumn(
                state    = listState,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding      = PaddingValues(vertical = 12.dp)
            ) {
                items(messages) { msg ->
                    HudMessageBubble(msg, messageFontSize)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgSecondary)
                    .border(0.5.dp, CardBorder, RoundedCornerShape(0.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                pendingImage?.let { img ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            bitmap             = img.bitmap.asImageBitmap(),
                            contentDescription = "Attached image",
                            modifier           = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .border(0.5.dp, NeonCyan.copy(0.5f), RoundedCornerShape(6.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "IMAGE ATTACHED",
                                fontSize      = 8.sp,
                                color         = NeonCyan,
                                letterSpacing = 1.sp,
                                fontFamily    = AppFontFamily
                            )
                            Text(
                                "// ready to transmit",
                                fontSize   = 8.sp,
                                color      = TextMuted,
                                fontFamily = AppFontFamily
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(CardBg)
                                .border(0.5.dp, CardBorder, RoundedCornerShape(4.dp))
                                .clickable { pendingImage = null },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✕", fontSize = 10.sp, color = TextMuted)
                        }
                    }
                }

                // تم تغيير المحاذاة لـ Bottom عشان الأزرار تنزل تحت لما مساحة النص تكبر
                Row(
                    modifier          = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    HudIconButton(
                        onClick = { cameraManager.launch() }
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = "Camera",
                            tint = TextPrimary, modifier = Modifier.size(17.dp))
                    }

                    Spacer(Modifier.width(6.dp))

                    OutlinedTextField(
                        value         = inputText,
                        onValueChange = { inputText = it },
                        placeholder   = {
                            Text(
                                text          = if (voiceState == VoiceState.PROCESSING)
                                    "TRANSCRIBING..." else "ENTER MESSAGE",
                                fontSize      = 10.sp,
                                color         = TextMuted,
                                letterSpacing = 1.sp,
                                fontFamily    = AppFontFamily
                            )
                        },
                        modifier        = Modifier.weight(1f),
                        shape           = RoundedCornerShape(6.dp),
                        // تم إلغاء السطر ده عشان النص يقبل أسطر متعددة
                        // singleLine      = true,
                        minLines        = 1, // أقل عدد أسطر
                        maxLines        = 5, // أقصى عدد أسطر قبل ما يعمل scroll
                        enabled         = voiceState != VoiceState.PROCESSING,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default), // عشان زرار Enter يطلع سطر جديد
                        keyboardActions = KeyboardActions(onSend = { sendMessage() }),
                        textStyle       = androidx.compose.ui.text.TextStyle(
                            fontFamily    = AppFontFamily,
                            fontSize      = 12.sp,
                            color         = TextPrimary,
                            letterSpacing = 0.5.sp
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = NeonGreen.copy(0.6f),
                            unfocusedBorderColor = CardBorder,
                            cursorColor          = NeonGreen,
                            focusedTextColor     = TextPrimary,
                            unfocusedTextColor   = TextPrimary
                        )
                    )

                    Spacer(Modifier.width(6.dp))

                    val micBg = when (voiceState) {
                        VoiceState.RECORDING  -> AccentPink.copy(0.15f)
                        VoiceState.PROCESSING -> CardBg
                        VoiceState.IDLE       -> CardBg
                    }
                    val micBorder = when (voiceState) {
                        VoiceState.RECORDING  -> AccentPink.copy(0.6f)
                        else                  -> CardBorder
                    }
                    HudIconButton(
                        background = micBg,
                        border     = micBorder,
                        onClick    = {
                            if (!hasMicPermission())
                                micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            else toggleMic()
                        }
                    ) {
                        Icon(
                            imageVector = when (voiceState) {
                                VoiceState.RECORDING  -> Icons.Default.Stop
                                VoiceState.PROCESSING -> Icons.Default.HourglassEmpty
                                VoiceState.IDLE       -> Icons.Default.Mic
                            },
                            contentDescription = "Mic",
                            tint = when (voiceState) {
                                VoiceState.RECORDING  -> AccentPink
                                VoiceState.PROCESSING -> TextMuted
                                VoiceState.IDLE       -> TextPrimary
                            },
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    Spacer(Modifier.width(6.dp))

                    HudIconButton(
                        background = if (isWaiting) CardBg else NeonGreen.copy(0.15f),
                        border     = if (isWaiting) CardBorder else NeonGreen.copy(0.5f),
                        onClick    = { sendMessage() }
                    ) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "Send",
                            tint     = if (isWaiting) TextMuted else NeonGreen,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }

        }

        AnimatedVisibility(
            visible  = showHistory,
            enter    = expandVertically(expandFrom = Alignment.Top),
            exit     = shrinkVertically(shrinkTowards = Alignment.Top),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            HudHistorySheet(
                context   = context,
                onLoad    = { loadSession(it) },
                onDelete  = { id ->
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val ok = ChatRepository.deleteSession(rawEndpoint, id)
                        if (ok) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (currentSessionId == id) {
                                    messages.clear()
                                    currentSessionId = ""
                                }
                            }
                        }
                    }
                },
                onDismiss = { showHistory = false }
            )
        }

        if (showSettings) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showSettings = false }
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                ) {
                    AnimatedVisibility(
                        visible  = showSettings,
                        enter    = expandVertically(expandFrom = Alignment.Top),
                        exit     = shrinkVertically(shrinkTowards = Alignment.Top)
                    ) {
                        HudSettingsSheet(
                            currentLocalProvider = localLlmProvider,
                            cohereApiKey = prefs.getString("cohere_api_key", "") ?: "",
                            cohereModel = prefs.getString("cohere_model", "command-a-plus-05-2026") ?: "command-a-plus-05-2026",
                            dahlApiKey = prefs.getString("dahl_api_key", "") ?: "",
                            dahlModel = prefs.getString("dahl_model", "MiniMaxAI/MiniMax-M2.7") ?: "MiniMaxAI/MiniMax-M2.7",
                            deepgramApiKey = prefs.getString("deepgram_api_key", "") ?: "",
                            onDeepgramApiKeyChange = { key ->
                                prefs.edit().putString("deepgram_api_key", key).apply()
                                deepgramApiKey = key
                            },
                            onLocalProviderChange = {
                                localLlmProvider = it
                                prefs.edit().putString("local_llm_provider", it.name).apply()
                                if (llmMode == LlmMode.LOCAL) {
                                    serverProvider.disconnect()
                                    localProvider.unloadModel()
                                    isConnected = false
                                    initProvider()
                                }
                            },
                            onCohereApiKeyChange = { key ->
                                prefs.edit().putString("cohere_api_key", key).apply()
                            },
                            onCohereModelChange = { model ->
                                prefs.edit().putString("cohere_model", model).apply()
                            },
                            onDahlApiKeyChange = { key ->
                                prefs.edit().putString("dahl_api_key", key).apply()
                            },
                            onDahlModelChange = { model ->
                                prefs.edit().putString("dahl_model", model).apply()
                            },
                            currentMode     = llmMode,
                            localModelState = localModelState,
                            currentBackend  = selectedBackend,
                            onModeChange    = { newMode ->
                                if (newMode == llmMode) return@HudSettingsSheet
                                when (newMode) {
                                    LlmMode.SERVER -> {
                                        localProvider.unloadModel()
                                        isConnected = false
                                        serverProvider.connect()
                                    }
                                    LlmMode.LOCAL -> {
                                        serverProvider.disconnect()
                                        isConnected = false
                                        localProvider.loadModel(
                                            backend   = selectedBackend,
                                            onSuccess = { isConnected = true },
                                            onError   = { err -> messages.add(ChatMessage("❌ $err", isUser = false)) }
                                        )
                                    }
                                }
                                llmMode = newMode
                                prefs.edit().putString("llm_mode", newMode.name).apply()
                            },
                            onBackendChange = { newBackend ->
                                selectedBackend = newBackend
                                prefs.edit().putString("llm_backend", newBackend).apply()
                            },
                            onLoadModel = {
                                localProvider.loadModel(
                                    backend   = selectedBackend,
                                    onSuccess = { isConnected = true },
                                    onError   = { err -> messages.add(ChatMessage("❌ $err", isUser = false)) }
                                )
                            },
                            onUnloadModel = {
                                localProvider.unloadModel()
                                isConnected = false
                            },
                            onDismiss = { showSettings = false },
                            sections = contextSections,
                            onSectionsRefresh = { refreshSections() },
                            messageFontSize = messageFontSize,
                            onMessageFontSizeChange = { newSize ->
                                messageFontSize = newSize
                                prefs.edit().putFloat("message_font_size", newSize).apply()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextSectionRow(
    section: ContextSection,
    context: android.content.Context,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleWidget: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = section.showOnWidget,
            onCheckedChange = { onToggleWidget(it) },
            colors = CheckboxDefaults.colors(
                checkedColor = NeonGreen,
                uncheckedColor = TextMuted
            ),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                section.title,
                color = TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = AppFontFamily
            )
            Text(
                section.content.take(60).replace("\n", " "),
                color = TextMuted,
                fontSize = 8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = AppFontFamily
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = "Edit", tint = NeonCyan, modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = AccentPink, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ContextSectionDialog(
    existing: ContextSection?,
    onDismiss: () -> Unit,
    onSave: (title: String, content: String, showOnWidget: Boolean) -> Unit
) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var content by remember { mutableStateOf(existing?.content ?: "") }
    var showOnWidget by remember { mutableStateOf(existing?.showOnWidget ?: true) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CardBg)
                .border(0.5.dp, CardBorder, RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Text(
                text = if (existing != null) "Edit Section" else "New Section",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title", color = TextMuted, fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = CardBorder,
                    focusedLabelColor = NeonCyan,
                    cursorColor = NeonCyan,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text("Content", color = TextMuted, fontSize = 13.sp) },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = CardBorder,
                    focusedLabelColor = NeonCyan,
                    cursorColor = NeonCyan,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = showOnWidget,
                    onCheckedChange = { showOnWidget = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = NeonGreen,
                        uncheckedColor = TextMuted
                    )
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Show on widget",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontFamily = AppFontFamily
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, CardBorder)
                ) { Text("Cancel", color = TextMuted) }

                Button(
                    onClick = { if (title.isNotBlank()) onSave(title, content, showOnWidget) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                    enabled = title.isNotBlank()
                ) { Text("Save", color = BgPrimary, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun ExpandableSection(
    title: String,
    accent: Color = NeonCyan,
    initiallyExpanded: Boolean = true,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(200)
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text          = title,
                fontSize      = 9.sp,
                color         = if (expanded) accent else TextMuted,
                letterSpacing = 1.5.sp,
                fontFamily    = AppFontFamily,
                fontWeight    = FontWeight.Bold
            )
            Text(
                text          = "▾",
                fontSize      = 10.sp,
                color         = if (expanded) accent else TextMuted,
                modifier      = Modifier.graphicsLayer { rotationZ = rotation }
            )
        }

        AnimatedVisibility(
            visible  = expanded,
            enter    = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit     = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
        ) {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                content()
            }
        }

        Divider(color = CardBorder, thickness = 0.5.dp)
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun HudIconButton(
    background: Color    = CardBg,
    border:     Color    = CardBorder,
    onClick:    () -> Unit,
    content:    @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .border(0.5.dp, border, RoundedCornerShape(6.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
        content          = { content() }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HudMessageBubble(
    msg: ChatMessage,
    fontSize: Float = 13f
) {
    val isUser = msg.isUser
    val context = LocalContext.current
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            Text(
                text       = "AX >",
                fontSize   = 8.sp,
                color      = NeonGreen.copy(0.6f),
                fontFamily = AppFontFamily,
                modifier   = Modifier
                    .padding(end = 6.dp)
                    .align(Alignment.Bottom)
                    .padding(bottom = 10.dp)
            )
        }

        Box(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(
                    RoundedCornerShape(
                        topStart    = if (isUser) 10.dp else 2.dp,
                        topEnd      = if (isUser) 2.dp else 10.dp,
                        bottomStart = 10.dp,
                        bottomEnd   = 10.dp
                    )
                )
                .background(
                    if (isUser) NeonGreen.copy(alpha = 0.08f)
                    else CardBg
                )
                .border(
                    0.5.dp,
                    if (isUser) NeonGreen.copy(0.3f) else CardBorder,
                    RoundedCornerShape(
                        topStart    = if (isUser) 10.dp else 2.dp,
                        topEnd      = if (isUser) 2.dp else 10.dp,
                        bottomStart = 10.dp,
                        bottomEnd   = 10.dp
                    )
                )
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        if (msg.text.isNotBlank() && msg.text != "📷 Image sent") {
                            clipboard.setPrimaryClip(ClipData.newPlainText("Copied Text", msg.text))
                            Toast.makeText(context, "Text copied", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            Column {
                msg.image?.let { bmp ->
                    Image(
                        bitmap             = bmp.asImageBitmap(),
                        contentDescription = "Sent image",
                        modifier           = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                    if (msg.text.isNotEmpty() && msg.text != "📷 Image sent")
                        Spacer(Modifier.height(6.dp))
                }

                if (msg.isTyping) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        repeat(3) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(NeonGreen.copy(0.5f))
                            )
                        }
                    }
                } else if (msg.text.isNotEmpty() && msg.text != "📷 Image sent") {
                    if (isUser) {
                        Text(
                            text       = msg.text,
                            color      = TextPrimary,
                            fontSize   = fontSize.sp,
                            fontFamily = AppFontFamily,
                            lineHeight = (fontSize + 6).sp
                        )
                    } else {
                        MarkdownText(
                            text       = msg.text,
                            fontSize   = fontSize,
                            color      = TextPrimary.copy(0.9f),
                            fontFamily = AppFontFamily
                        )
                    }
                }
            }
        }

        if (isUser) {
            Text(
                text       = "< ME",
                fontSize   = 8.sp,
                color      = NeonCyan.copy(0.6f),
                fontFamily = AppFontFamily,
                modifier   = Modifier
                    .padding(start = 6.dp)
                    .align(Alignment.Bottom)
                    .padding(bottom = 10.dp)
            )
        }
    }
}

@Composable
private fun HudHistorySheet(
    context:   android.content.Context,
    onLoad:    (ChatSession) -> Unit,
    onDelete:  (String) -> Unit,
    onDismiss: () -> Unit
) {
    var sessions by remember { mutableStateOf(emptyList<ChatSession>()) }
    val historyScope = rememberCoroutineScope()
    val prefs2       = context.getSharedPreferences("axon_prefs", android.content.Context.MODE_PRIVATE)
    val ep           = prefs2.getString("endpoint", MainActivity.PRESET_ENDPOINTS[0])
        ?: MainActivity.PRESET_ENDPOINTS[0]

    fun refresh() {
        historyScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val result = ChatRepository.fetchSessions(ep, context)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                sessions = result
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 400.dp)
            .background(BgSecondary)
            .border(0.5.dp, CardBorder, RoundedCornerShape(0.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text          = "// SESSION LOG",
                fontSize      = 11.sp,
                fontWeight    = FontWeight.Bold,
                color         = TextPrimary,
                letterSpacing = 2.sp,
                fontFamily    = AppFontFamily,
                modifier      = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(CardBg)
                    .border(0.5.dp, CardBorder, RoundedCornerShape(4.dp))
                    .clickable { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                Text("✕", fontSize = 10.sp, color = TextMuted)
            }
        }

        Divider(color = CardBorder, thickness = 0.5.dp)

        if (sessions.isEmpty()) {
            Box(
                modifier         = Modifier
                    .fillMaxWidth()
                    .height(100.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "// NO SESSIONS FOUND",
                    fontSize      = 10.sp,
                    color         = TextMuted,
                    letterSpacing = 1.sp,
                    fontFamily    = AppFontFamily
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(
                    items = sessions,
                    key = { it.id }
                ) { session ->
                    HudSessionItem(
                        session = session,
                        onClick = { onLoad(session) },
                        onDelete = { onDelete(session.id); refresh() }
                    )
                    Divider(color = CardBorder, thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
private fun HudSessionItem(
    session:  ChatSession,
    onClick:  () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text       = ">",
            fontSize   = 10.sp,
            color      = NeonGreen.copy(0.5f),
            fontFamily = AppFontFamily,
            modifier   = Modifier.padding(end = 8.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text          = session.title.uppercase(),
                color         = TextPrimary,
                fontSize      = 11.sp,
                fontWeight    = FontWeight.Medium,
                letterSpacing = 0.5.sp,
                fontFamily    = AppFontFamily,
                maxLines      = 1,
                overflow      = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text          = "${ChatRepository.formatDate(session.createdAt)}  //  ${session.messages.size} MSG",
                color         = TextMuted,
                fontSize      = 8.sp,
                letterSpacing = 0.5.sp,
                fontFamily    = AppFontFamily
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(AccentPink.copy(0.08f))
                .border(0.5.dp, AccentPink.copy(0.3f), RoundedCornerShape(4.dp))
                .clickable { onDelete() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete",
                tint     = AccentPink.copy(0.8f),
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

private fun generateSingleConfirmation(json: JSONObject): String {
    return try {
        val action = json.optString("action", "done")
        val params = json.optJSONObject("params")
        when (action) {
            "open_app" -> {
                val app = params?.optString("app_name", "app") ?: "app"
                "$app opened"
            }
            "call" -> {
                val contact = params?.optString("contact_name", "") ?: ""
                val num = params?.optString("number", "") ?: ""
                when {
                    contact.isNotBlank() -> "Calling $contact"
                    num.isNotBlank() -> "Dialing $num"
                    else -> "Calling"
                }
            }
            "set_alarm" -> {
                val h = params?.optInt("hour", 0) ?: 0
                val m = params?.optInt("minute", 0) ?: 0
                "Alarm set for ${String.format("%02d:%02d", h, m)}"
            }
            "send_sms" -> "Message sent"
            "wifi_toggle" -> if (params?.optBoolean("enable", true) == true) "WiFi on" else "WiFi off"
            "bluetooth_toggle" -> if (params?.optBoolean("enable", true) == true) "Bluetooth on" else "Bluetooth off"
            "flashlight_toggle" -> if (params?.optBoolean("enable", true) == true) "Flashlight on" else "Flashlight off"
            "volume_up" -> "Volume up"
            "volume_down" -> "Volume lowered"
            "volume_mute" -> "Muted"
            "volume_unmute" -> "Unmuted"
            "screenshot" -> "Screenshot taken"
            "lock_phone", "screen_lock" -> "Locked"
            "navigate_to" -> "Navigating to ${params?.optString("destination", "destination")}"
            "take_photo" -> "Camera ready"
            "record_video" -> "Recording"
            "battery_status" -> "Battery checked"
            "memory_status" -> "Memory checked"
            "desktop_task" -> "Forwarded to desktop"
            else -> "Done"
        }
    } catch (_: Exception) { "Done" }
}

private fun generateMultiConfirmation(actions: List<JSONObject>): String {
    return actions.joinToString("\n") { generateSingleConfirmation(it) }
}

@Composable
private fun HudSettingsSheet(
    currentMode:     LlmMode,
    localModelState: LocalModelState,
    currentBackend:  String,
    onModeChange:    (LlmMode) -> Unit,
    onBackendChange: (String) -> Unit,
    onLoadModel:     () -> Unit,
    onUnloadModel:   () -> Unit,
    onDismiss:       () -> Unit,
    sections:     List<ContextSection> = emptyList(),
    onSectionsRefresh: () -> Unit = {},
    currentLocalProvider: LocalLlmProviderType = LocalLlmProviderType.GEMMA_4B,
    cohereApiKey: String = "",
    cohereModel: String = "command-a-plus-05-2026",
    dahlApiKey: String = "",
    dahlModel: String = "MiniMaxAI/MiniMax-M2.7",
    onLocalProviderChange: (LocalLlmProviderType) -> Unit = {},
    onCohereApiKeyChange: (String) -> Unit = {},
    onCohereModelChange: (String) -> Unit = {},
    onDahlApiKeyChange: (String) -> Unit = {},
    onDahlModelChange: (String) -> Unit = {},
    messageFontSize: Float = 13f,
    onMessageFontSizeChange: (Float) -> Unit = {},
    deepgramApiKey: String = "",
    onDeepgramApiKeyChange: (String) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgSecondary)
            .border(0.5.dp, CardBorder, RoundedCornerShape(0.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "// SYSTEM CONFIG",
                fontSize      = 11.sp,
                fontWeight    = FontWeight.Bold,
                color         = TextPrimary,
                letterSpacing = 2.sp,
                fontFamily    = AppFontFamily,
                modifier      = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(CardBg)
                    .border(0.5.dp, CardBorder, RoundedCornerShape(4.dp))
                    .clickable { onDismiss() },
                contentAlignment = Alignment.Center
            ) { Text("✕", fontSize = 10.sp, color = TextMuted) }
        }

        Spacer(Modifier.height(12.dp))

        ExpandableSection(
            title = "◈ DISPLAY",
            accent = NeonCyan,
            initiallyExpanded = false
        ) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "MESSAGE FONT SIZE",
                    fontSize      = 9.sp,
                    color         = TextMuted,
                    letterSpacing = 1.sp,
                    fontFamily    = AppFontFamily
                )
                Text(
                    "${messageFontSize.toInt()} SP",
                    fontSize      = 9.sp,
                    color         = NeonCyan,
                    letterSpacing = 1.sp,
                    fontFamily    = AppFontFamily,
                    fontWeight    = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(4.dp))

            Slider(
                value         = messageFontSize,
                onValueChange = { newValue ->
                    val rounded = (newValue * 2).toInt() / 2f
                    onMessageFontSizeChange(rounded)
                },
                valueRange    = 10f..22f,
                steps         = 23,
                colors        = SliderDefaults.colors(
                    thumbColor          = NeonCyan,
                    activeTrackColor    = NeonCyan.copy(0.6f),
                    inactiveTrackColor  = CardBorder,
                    activeTickColor     = NeonCyan.copy(0.3f),
                    inactiveTickColor   = CardBorder
                )
            )

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(11f to "S", 13f to "M", 15f to "L", 18f to "XL").forEach { (size, label) ->
                    val isSelected = messageFontSize.toInt() == size.toInt()
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isSelected) NeonCyan.copy(0.12f) else CardBg)
                            .border(
                                0.5.dp,
                                if (isSelected) NeonCyan.copy(0.5f) else CardBorder,
                                RoundedCornerShape(4.dp)
                            )
                            .clickable { onMessageFontSizeChange(size) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            fontSize   = 9.sp,
                            color      = if (isSelected) NeonCyan else TextMuted,
                            fontFamily = AppFontFamily,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(CardBg)
                    .border(0.5.dp, CardBorder, RoundedCornerShape(6.dp))
                    .padding(10.dp)
            ) {
                MarkdownText(
                    text = "# Heading 1\n## Heading 2\nNormal **bold** and `code`\n- List item",
                    fontSize = messageFontSize,
                    color = TextPrimary
                )
            }
        }

        // ═══════════════════════════════════════════════════════════
        //  DEEPGRAM STT API SECTION
        // ═══════════════════════════════════════════════════════════
        ExpandableSection(
            title = "◈ VOICE / STT",
            accent = NeonCyan,
            initiallyExpanded = false
        ) {
            Spacer(Modifier.height(8.dp))

            var draftKey by remember { mutableStateOf(deepgramApiKey) }
            OutlinedTextField(
                value         = draftKey,
                onValueChange = { draftKey = it },
                label         = {
                    Text(
                        "DEEPGRAM API KEY",
                        fontSize      = 9.sp,
                        color         = TextMuted,
                        fontFamily    = AppFontFamily
                    )
                },
                singleLine = true,
                modifier   = Modifier.fillMaxWidth(),
                colors     = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = NeonCyan,
                    unfocusedBorderColor = CardBorder,
                    focusedLabelColor    = NeonCyan,
                    cursorColor          = NeonCyan,
                    focusedTextColor     = TextPrimary,
                    unfocusedTextColor   = TextPrimary
                ),
                textStyle = TextStyle(
                    fontFamily    = AppFontFamily,
                    fontSize      = 11.sp
                )
            )
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(NeonCyan.copy(0.1f))
                    .border(0.5.dp, NeonCyan.copy(0.4f), RoundedCornerShape(4.dp))
                    .clickable { onDeepgramApiKeyChange(draftKey) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "SAVE KEY",
                    fontSize      = 9.sp,
                    color         = NeonCyan,
                    letterSpacing = 1.sp,
                    fontFamily    = AppFontFamily
                )
            }
        }

        ExpandableSection(
            title = "◈ INFERENCE MODE",
            accent = NeonCyan,
            initiallyExpanded = false
        ) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LlmMode.values().forEach { mode ->
                    val isSelected = currentMode == mode
                    val accent = if (mode == LlmMode.SERVER) NeonCyan else NeonGreen
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) accent.copy(0.12f) else CardBg)
                            .border(
                                0.5.dp,
                                if (isSelected) accent.copy(0.6f) else CardBorder,
                                RoundedCornerShape(6.dp)
                            )
                            .clickable { onModeChange(mode) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text          = if (mode == LlmMode.SERVER) "◈ SERVER" else "◉ LOCAL",
                            fontSize      = 10.sp,
                            color         = if (isSelected) accent else TextMuted,
                            letterSpacing = 1.sp,
                            fontFamily    = AppFontFamily
                        )
                    }
                }
            }
        }

        if (currentMode == LlmMode.LOCAL) {
            ExpandableSection(
                title = "◉ LOCAL ENGINE",
                accent = NeonGreen,
                initiallyExpanded = false
            ) {
                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LocalLlmProviderType.values().forEach { provider ->
                        val isSelected = currentLocalProvider == provider
                        val accent = when (provider) {
                            LocalLlmProviderType.GEMMA_4B -> NeonGreen
                            LocalLlmProviderType.COHERE_API -> AccentPink
                            LocalLlmProviderType.DAHL_API -> NeonCyan
                        }
                        val label = when (provider) {
                            LocalLlmProviderType.GEMMA_4B -> "◈ GEMMA 4B"
                            LocalLlmProviderType.COHERE_API -> "◉ COHERE"
                            LocalLlmProviderType.DAHL_API -> "◉ DAHL"
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) accent.copy(0.12f) else CardBg)
                                .border(
                                    0.5.dp,
                                    if (isSelected) accent.copy(0.6f) else CardBorder,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable { onLocalProviderChange(provider) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text          = label,
                                fontSize      = 10.sp,
                                color         = if (isSelected) accent else TextMuted,
                                letterSpacing = 1.sp,
                                fontFamily    = AppFontFamily
                            )
                        }
                    }
                }

                if (currentLocalProvider == LocalLlmProviderType.COHERE_API) {
                    Spacer(Modifier.height(12.dp))

                    var draftKey by remember { mutableStateOf(cohereApiKey) }
                    OutlinedTextField(
                        value         = draftKey,
                        onValueChange = { draftKey = it },
                        label         = {
                            Text(
                                "COHERE API KEY",
                                fontSize      = 9.sp,
                                color         = TextMuted,
                                fontFamily    = AppFontFamily
                            )
                        },
                        singleLine = true,
                        modifier   = Modifier.fillMaxWidth(),
                        colors     = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = AccentPink,
                            unfocusedBorderColor = CardBorder,
                            focusedLabelColor    = AccentPink,
                            cursorColor          = AccentPink,
                            focusedTextColor     = TextPrimary,
                            unfocusedTextColor   = TextPrimary
                        ),
                        textStyle = TextStyle(
                            fontFamily    = AppFontFamily,
                            fontSize      = 11.sp
                        )
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(AccentPink.copy(0.1f))
                            .border(0.5.dp, AccentPink.copy(0.4f), RoundedCornerShape(4.dp))
                            .clickable { onCohereApiKeyChange(draftKey) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "SAVE KEY",
                            fontSize      = 9.sp,
                            color         = AccentPink,
                            letterSpacing = 1.sp,
                            fontFamily    = AppFontFamily
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    var modelExpanded by remember { mutableStateOf(false) }
                    Box {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(CardBg)
                                .border(0.5.dp, AccentPink.copy(0.4f), RoundedCornerShape(6.dp))
                                .clickable { modelExpanded = true },
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment     = Alignment.CenterVertically
                            ) {
                                Text(
                                    "◈ $cohereModel",
                                    fontSize      = 10.sp,
                                    color         = AccentPink,
                                    letterSpacing = 1.sp,
                                    fontFamily    = AppFontFamily
                                )
                                Text("▾", fontSize = 10.sp, color = AccentPink)
                            }
                        }

                        DropdownMenu(
                            expanded         = modelExpanded,
                            onDismissRequest = { modelExpanded = false },
                            modifier         = Modifier
                                .background(BgSecondary)
                                .border(0.5.dp, CardBorder, RoundedCornerShape(6.dp))
                        ) {
                            CohereLlmProvider.AVAILABLE_MODELS.forEach { model ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "◈ $model",
                                            fontSize      = 10.sp,
                                            color         = if (model == cohereModel) AccentPink else TextPrimary,
                                            letterSpacing = 1.sp,
                                            fontFamily    = AppFontFamily
                                        )
                                    },
                                    onClick = {
                                        modelExpanded = false
                                        onCohereModelChange(model)
                                    }
                                )
                            }
                        }
                    }
                }

                if (currentLocalProvider == LocalLlmProviderType.DAHL_API) {
                    Spacer(Modifier.height(12.dp))

                    var draftKey by remember { mutableStateOf(dahlApiKey) }
                    OutlinedTextField(
                        value         = draftKey,
                        onValueChange = { draftKey = it },
                        label         = {
                            Text(
                                "DAHL API KEY",
                                fontSize      = 9.sp,
                                color         = TextMuted,
                                fontFamily    = AppFontFamily
                            )
                        },
                        singleLine = true,
                        modifier   = Modifier.fillMaxWidth(),
                        colors     = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = NeonCyan,
                            unfocusedBorderColor = CardBorder,
                            focusedLabelColor    = NeonCyan,
                            cursorColor          = NeonCyan,
                            focusedTextColor     = TextPrimary,
                            unfocusedTextColor   = TextPrimary
                        ),
                        textStyle = TextStyle(
                            fontFamily    = AppFontFamily,
                            fontSize      = 11.sp
                        )
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(NeonCyan.copy(0.1f))
                            .border(0.5.dp, NeonCyan.copy(0.4f), RoundedCornerShape(4.dp))
                            .clickable { onDahlApiKeyChange(draftKey) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "SAVE KEY",
                            fontSize      = 9.sp,
                            color         = NeonCyan,
                            letterSpacing = 1.sp,
                            fontFamily    = AppFontFamily
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    var modelExpanded by remember { mutableStateOf(false) }
                    Box {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(CardBg)
                                .border(0.5.dp, NeonCyan.copy(0.4f), RoundedCornerShape(6.dp))
                                .clickable { modelExpanded = true },
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment     = Alignment.CenterVertically
                            ) {
                                Text(
                                    "◈ $dahlModel",
                                    fontSize      = 10.sp,
                                    color         = NeonCyan,
                                    letterSpacing = 1.sp,
                                    fontFamily    = AppFontFamily
                                )
                                Text("▾", fontSize = 10.sp, color = NeonCyan)
                            }
                        }

                        DropdownMenu(
                            expanded         = modelExpanded,
                            onDismissRequest = { modelExpanded = false },
                            modifier         = Modifier
                                .background(BgSecondary)
                                .border(0.5.dp, CardBorder, RoundedCornerShape(6.dp))
                        ) {
                            DahlLlmProvider.AVAILABLE_MODELS.forEach { model ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "◈ $model",
                                            fontSize      = 10.sp,
                                            color         = if (model == dahlModel) NeonCyan else TextPrimary,
                                            letterSpacing = 1.sp,
                                            fontFamily    = AppFontFamily
                                        )
                                    },
                                    onClick = {
                                        modelExpanded = false
                                        onDahlModelChange(model)
                                    }
                                )
                            }
                        }
                    }
                }

                if (currentLocalProvider == LocalLlmProviderType.GEMMA_4B && localModelState == LocalModelState.LOADED) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "// UNLOAD MODEL TO CHANGE BACKEND",
                        fontSize      = 7.sp,
                        color         = TextMuted.copy(0.6f),
                        letterSpacing = 1.sp,
                        fontFamily    = AppFontFamily
                    )
                }
            }
        }

        val hudContext = LocalContext.current
        var showSectionDialog by remember { mutableStateOf(false) }
        var editSectionTarget by remember { mutableStateOf<ContextSection?>(null) }

        ExpandableSection(
            title = "◈ CONTEXT SECTIONS  //  ${sections.size} / ${ContextSectionStore.MAX_SECTIONS}",
            accent = NeonCyan,
            initiallyExpanded = false
        ) {
            Spacer(Modifier.height(8.dp))

            if (sections.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "// NO SECTIONS YET",
                        fontSize = 9.sp,
                        color = TextMuted,
                        letterSpacing = 1.sp,
                        fontFamily = AppFontFamily
                    )
                }
            } else {
                sections.sortedBy { it.order }.forEach { section ->
                    ContextSectionRow(
                        section = section,
                        context = hudContext,
                        onEdit = { editSectionTarget = section; showSectionDialog = true },
                        onDelete = {
                            ContextSectionStore.delete(hudContext, section.id)
                            onSectionsRefresh()
                        },
                        onToggleWidget = { show ->
                            ContextSectionStore.update(
                                hudContext, section.id,
                                section.title, section.content, show
                            )
                            onSectionsRefresh()
                        }
                    )
                    Divider(color = CardBorder, thickness = 0.5.dp)
                }
            }

            Spacer(Modifier.height(8.dp))

            if (sections.size < ContextSectionStore.MAX_SECTIONS) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(NeonCyan.copy(0.08f))
                        .border(0.5.dp, NeonCyan.copy(0.3f), RoundedCornerShape(6.dp))
                        .clickable { editSectionTarget = null; showSectionDialog = true }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "+ ADD SECTION",
                        fontSize = 9.sp,
                        color = NeonCyan,
                        letterSpacing = 1.sp,
                        fontFamily = AppFontFamily
                    )
                }
            }
        }

        if (showSectionDialog) {
            ContextSectionDialog(
                existing = editSectionTarget,
                onDismiss = { showSectionDialog = false },
                onSave = { title, content, showOnWidget ->
                    if (editSectionTarget != null) {
                        ContextSectionStore.update(
                            hudContext, editSectionTarget!!.id,
                            title, content, showOnWidget
                        )
                    } else {
                        ContextSectionStore.add(hudContext, title, content, showOnWidget)
                    }
                    onSectionsRefresh()
                    showSectionDialog = false
                }
            )
        }
    }
}