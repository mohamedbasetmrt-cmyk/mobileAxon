package com.example.app_abdelbaset

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ChatSummaryManager - مسئول عن حفظ واسترجاع ملخصات المحادثات
 * 
 * المميزات:
 * 1. حفظ كل محادثة كملف JSON منفصل
 * 2. إنشاء ملخص تلقائي لكل محادثة
 * 3. استرجاع السياق من الملخصات عند الحاجة
 * 4. دعم البحث في المحادثات القديمة
 */
data class ChatSummary(
    val sessionId: String,
    val title: String,
    val summary: String,
    val keyPoints: List<String>,
    val messageCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val tags: List<String> = emptyList(),
    val serverNodeId: String = ""
)

object ChatSummaryManager {
    private const val TAG = "ChatSummaryManager"
    private const val SUMMARIES_DIR = "chat_summaries"
    private const val CHATS_DIR = "chat_sessions"

    private lateinit var appContext: Context
    private lateinit var summariesDir: File
    private lateinit var chatsDir: File

    // Scope + mutex على مستوى الـ app — بيستحملوا خروج ChatScreen من الـ composition
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()

    private fun isUuid(id: String): Boolean = id.contains("-") && id.length >= 30

    fun init(context: Context) {
        appContext = context.applicationContext
        summariesDir = File(appContext.filesDir, SUMMARIES_DIR)
        chatsDir = File(appContext.filesDir, CHATS_DIR)

        if (!summariesDir.exists()) summariesDir.mkdirs()
        if (!chatsDir.exists()) chatsDir.mkdirs()
    }

    /**
     * حفظ محلي + sync على السيرفر بضمانة "مرة واحدة بس":
     * - الـ POST (create) بيحصل مرة واحدة لكل شات محلي (UUID)
     * - الشات البعيد اللي اتحمّل من الـ History (id رقمي) بيتعمل له PUT مباشرة
     * - الـ mutex بيسلسل العمليات والـ scope app-level فمش بيموت مع خروج الشاشة
     */
    fun saveSessionAndSync(endpoint: String, messages: List<ChatMessage>, sessionId: String) {
        syncScope.launch {
            syncMutex.withLock {
                try {
                    // 1. استرجع الـ serverNodeId القديم BEFORE الحفظ
                    val oldSummary = getSummary(sessionId)
                    val oldNodeId = oldSummary?.serverNodeId.orEmpty()

                    // 2. احفظ المحادثة والملخص محلياً
                    saveSession(messages, sessionId)

                    // 3. استخدم الـ serverNodeId القديم (لو موجود)
                    var nodeId = oldNodeId

                    if (nodeId.isBlank() && !isUuid(sessionId)) {
                        // شات بعيد — الـ id بتاعه هو الـ node id نفسه على السيرفر
                        nodeId = sessionId
                        setServerNodeId(sessionId, nodeId)
                    }

                    if (nodeId.isBlank()) {
                        // شات محلي جديد — نعمل create مرة واحدة بس
                        val newId = ChatRepository.saveSession(endpoint, messages)
                        if (newId.isNotEmpty()) setServerNodeId(sessionId, newId)
                    } else {
                        ChatRepository.updateSession(endpoint, nodeId, messages)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in saveSessionAndSync: ${e.message}")
                }
            }
        }
    }

    /**
     * حفظ محادثة جديدة مع إنشاء ملخص لها
     */
    fun saveSession(messages: List<ChatMessage>, sessionId: String): Boolean {
        try {
            // 1. حفظ المحادثة الكاملة
            val chatFile = File(chatsDir, "$sessionId.json")
            val chatJson = buildChatJson(messages, sessionId)
            chatFile.writeText(chatJson.toString())

            // 2. تحديث الملخص (أو إنشاؤه لو مش موجود)
            val summaryFile = File(summariesDir, "$sessionId.json")
            val existingSummary = if (summaryFile.exists()) {
                JSONObject(summaryFile.readText())
            } else null

            val summary = generateSummary(messages)
            val keyPoints = extractKeyPoints(messages)
            val title = generateTitle(messages)
            val tags = extractTags(messages)

            // 3. حفظ الملخص (نحافظ على serverNodeId لو موجود)
            val summaryObj = existingSummary ?: JSONObject()
            summaryObj.apply {
                put("sessionId", sessionId)
                put("title", title)
                put("summary", summary)
                put("keyPoints", JSONArray(keyPoints))
                put("messageCount", messages.size)
                put("updatedAt", System.currentTimeMillis())
                put("tags", JSONArray(tags))
                // نحافظ على createdAt الأصلي لو موجود
                if (!has("createdAt")) put("createdAt", System.currentTimeMillis())
                // نحافظ على serverNodeId الأصلي لو موجود
                if (!has("serverNodeId")) put("serverNodeId", "")
            }
            summaryFile.writeText(summaryObj.toString())

            // ── NEW: Record in ServiceStatsTracker for real-time Dashboard display ──
            ServiceStatsTracker.recordConversationSummary(
                sessionId = sessionId,
                messageCount = messages.size,
                summary = summary
            )

            Log.d(TAG, "Saved session $sessionId with ${messages.size} messages")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error saving session: ${e.message}")
            return false
        }
    }

    /**
     * تحديث ملخص محادثة موجودة
     */
    fun updateSummary(sessionId: String, messages: List<ChatMessage>): Boolean {
        try {
            val summaryFile = File(summariesDir, "$sessionId.json")
            if (!summaryFile.exists()) return false

            val summaryObj = JSONObject(summaryFile.readText())
            val newSummary = generateSummary(messages)
            val newKeyPoints = extractKeyPoints(messages)

            summaryObj.apply {
                put("summary", newSummary)
                put("keyPoints", JSONArray(newKeyPoints))
                put("messageCount", messages.size)
                put("updatedAt", System.currentTimeMillis())
            }

            summaryFile.writeText(summaryObj.toString())
            Log.d(TAG, "Updated summary for session $sessionId")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error updating summary: ${e.message}")
            return false
        }
    }

    /**
     * ربط الجلسة المحلية بالـ node_id بتاعها على السيرفر
     * (عشان الـ History يدمج النسخة المحلية مع البعيدة من غير duplicates)
     */
    fun setServerNodeId(sessionId: String, serverNodeId: String) {
        try {
            val summaryFile = File(summariesDir, "$sessionId.json")
            if (!summaryFile.exists()) return
            val obj = JSONObject(summaryFile.readText())
            obj.put("serverNodeId", serverNodeId)
            summaryFile.writeText(obj.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error setting server node id: ${e.message}")
        }
    }

    /**
     * استرجاع ملخص محادثة معينة
     */
    fun getSummary(sessionId: String): ChatSummary? {
        return try {
            val summaryFile = File(summariesDir, "$sessionId.json")
            if (!summaryFile.exists()) return null

            val obj = JSONObject(summaryFile.readText())
            ChatSummary(
                sessionId = obj.getString("sessionId"),
                title = obj.getString("title"),
                summary = obj.getString("summary"),
                keyPoints = JSONArray(obj.optString("keyPoints", "[]")).let { arr ->
                    List(arr.length()) { arr.optString(it) }
                },
                messageCount = obj.getInt("messageCount"),
                createdAt = obj.getLong("createdAt"),
                updatedAt = obj.getLong("updatedAt"),
                tags = JSONArray(obj.optString("tags", "[]")).let { arr ->
                    List(arr.length()) { arr.optString(it) }
                },
                serverNodeId = obj.optString("serverNodeId", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting summary: ${e.message}")
            null
        }
    }

    /**
     * استرجاع كل الملخصات
     */
    fun getAllSummaries(): List<ChatSummary> {
        return try {
            summariesDir.listFiles { f -> f.name.endsWith(".json") }
                ?.mapNotNull { file ->
                    try {
                        val obj = JSONObject(file.readText())
                        ChatSummary(
                            sessionId = obj.getString("sessionId"),
                            title = obj.getString("title"),
                            summary = obj.getString("summary"),
                            keyPoints = JSONArray(obj.optString("keyPoints", "[]")).let { arr ->
                                List(arr.length()) { arr.optString(it) }
                            },
                            messageCount = obj.getInt("messageCount"),
                            createdAt = obj.getLong("createdAt"),
                            updatedAt = obj.getLong("updatedAt"),
                            tags = JSONArray(obj.optString("tags", "[]")).let { arr ->
                                List(arr.length()) { arr.optString(it) }
                            },
                            serverNodeId = obj.optString("serverNodeId", "")
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Error parsing ${file.name}: ${e.message}")
                        null
                    }
                }
                // تجميع النسخ المكررة لنفس الـ serverNodeId وأخذ الأحدث فقط
                ?.groupBy { it.serverNodeId.ifBlank { it.sessionId } }
                ?.map { (_, list) -> list.maxByOrNull { it.updatedAt } ?: list.first() }
                ?.sortedByDescending { it.updatedAt }
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting all summaries: ${e.message}")
            emptyList()
        }
    }

    /**
     * البحث في الملخصات عن كلمة مفتاحية
     */
    fun searchSummaries(query: String): List<ChatSummary> {
        val lowerQuery = query.lowercase()
        return getAllSummaries().filter { summary ->
            summary.title.lowercase().contains(lowerQuery) ||
                    summary.summary.lowercase().contains(lowerQuery) ||
                    summary.keyPoints.any { it.lowercase().contains(lowerQuery) } ||
                    summary.tags.any { it.lowercase().contains(lowerQuery) }
        }
    }

    /**
     * استرجاع المحادثة الكاملة
     */
    fun getFullSession(sessionId: String): List<ChatMessage>? {
        return try {
            val chatFile = File(chatsDir, "$sessionId.json")
            if (!chatFile.exists()) return null

            val obj = JSONObject(chatFile.readText())
            val messagesArr = obj.getJSONArray("messages")
            List(messagesArr.length()) { i ->
                val msg = messagesArr.getJSONObject(i)
                val imagePath = msg.optString("imagePath", "")
                ChatMessage(
                    text = msg.getString("text"),
                    isUser = msg.getString("role") == "user",
                    image = if (imagePath.isNotBlank()) loadChatImage(imagePath) else null,
                    references = msg.optJSONArray("references")?.let { refsArr ->
                        List(refsArr.length()) { j ->
                            val ref = refsArr.getJSONObject(j)
                            AiReference(
                                title = ref.optString("title", ""),
                                url = ref.optString("url", ""),
                                description = ref.optString("description", "")
                            )
                        }
                    } ?: emptyList()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting full session: ${e.message}")
            null
        }
    }

    /**
     * بناء سياق ذكي من الملخصات للإجابة على سؤال معين
     * دي الوظيفة اللي هتستخدمها قبل ما تبعت السؤال للـ LLM
     */
    fun buildSmartContext(
        currentMessages: List<ChatMessage>,
        userQuestion: String,
        maxSummaries: Int = 3
    ): String {
        // 1. استخراج الكلمات المفتاحية من السؤال
        val keywords = extractKeywords(userQuestion)

        // 2. البحث في الملخصات عن أقرب سياق
        val relevantSummaries = if (keywords.isNotEmpty()) {
            getAllSummaries().filter { summary ->
                keywords.any { kw ->
                    summary.summary.lowercase().contains(kw) ||
                            summary.keyPoints.any { kp -> kp.lowercase().contains(kw) } ||
                            summary.tags.any { tag -> tag.lowercase().contains(kw) }
                }
            }.take(maxSummaries)
        } else {
            // لو مفيش كلمات مفتاحية واضحة، خد آخر 3 محادثات
            getAllSummaries().take(maxSummaries)
        }

        // 3. بناء الـ context block
        if (relevantSummaries.isEmpty()) {
            return ""
        }

        val contextBuilder = StringBuilder()
        contextBuilder.appendLine("\n--- RELEVANT PAST CONVERSATIONS ---")
        relevantSummaries.forEachIndexed { index, summary ->
            contextBuilder.appendLine("\n[Conversation ${index + 1}: ${summary.title}]")
            contextBuilder.appendLine("Summary: ${summary.summary}")
            if (summary.keyPoints.isNotEmpty()) {
                contextBuilder.appendLine("Key Points:")
                summary.keyPoints.forEach { kp ->
                    contextBuilder.appendLine("  • $kp")
                }
            }
        }
        contextBuilder.appendLine("--- END PAST CONVERSATIONS ---\n")

        return contextBuilder.toString()
    }

    /**
     * حذف محادثة وملخصها
     */
    fun deleteSession(sessionId: String): Boolean {
        return try {
            File(chatsDir, "$sessionId.json").delete()
            File(summariesDir, "$sessionId.json").delete()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting session: ${e.message}")
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers - توليد الملخصات والكلمات المفتاحية
    // ═══════════════════════════════════════════════════════════════

    private fun buildChatJson(messages: List<ChatMessage>, sessionId: String): JSONObject {
        val msgsArr = JSONArray()
        messages.forEachIndexed { index, m ->
            val msgObj = JSONObject().apply {
                put("role", if (m.isUser) "user" else "assistant")
                put("text", m.text)
                put("timestamp", System.currentTimeMillis())

                // حفظ الصورة كملف داخل التطبيق وحفظ المسار في الـ JSON
                m.image?.let { bmp ->
                    saveChatImage(sessionId, index, bmp)?.let { path ->
                        put("imagePath", path)
                    }
                }

                // حفظ المراجع
                if (m.references.isNotEmpty()) {
                    val refsArr = JSONArray()
                    m.references.forEach { ref ->
                        refsArr.put(JSONObject().apply {
                            put("title", ref.title)
                            put("url", ref.url)
                            put("description", ref.description)
                        })
                    }
                    put("references", refsArr)
                }
            }

            msgsArr.put(msgObj)
        }

        return JSONObject().apply {
            put("sessionId", sessionId)
            put("messages", msgsArr)
            put("createdAt", System.currentTimeMillis())
        }
    }

    private fun saveChatImage(sessionId: String, index: Int, bitmap: Bitmap): String? {
        return try {
            val dir = File(appContext.filesDir, "chat_images")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "${sessionId}_$index.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error saving chat image: ${e.message}")
            null
        }
    }

    private fun loadChatImage(path: String): Bitmap? {
        return try {
            val file = File(path)
            if (file.exists()) BitmapFactory.decodeFile(path) else null
        } catch (e: Exception) {
            Log.e(TAG, "Error loading chat image: ${e.message}")
            null
        }
    }

    private fun generateSummary(messages: List<ChatMessage>): String {
        if (messages.isEmpty()) return "Empty conversation"

        val userMessages = messages.filter { it.isUser }.map { it.text.trim() }
        val assistantMessages = messages.filter { !it.isUser }.map { it.text.trim() }

        val sb = StringBuilder()
        sb.append("Conversation of ${messages.size} messages.\n")

        if (userMessages.isNotEmpty()) {
            sb.append("User asked/intended:\n")
            userMessages.take(6).forEach { sb.append("  • ${summaryLine(it)}\n") }
        }
        if (assistantMessages.isNotEmpty()) {
            sb.append("Key assistant replies:\n")
            assistantMessages.take(4).forEach { sb.append("  • ${summaryLine(it)}\n") }
        }

        return sb.toString().trim()
    }

    private fun summaryLine(text: String): String {
        val clean = text.replace("\n", " ").trim()
        return if (clean.length > 100) clean.take(100) + "..." else clean
    }

    private fun extractKeyPoints(messages: List<ChatMessage>): List<String> {
        val keyPoints = mutableListOf<String>()

        // استخراج المعلومات المهمة من ردود الـ assistant
        messages.filter { !it.isUser }.forEach { msg ->
            val text = msg.text.trim()
            if (text.contains(":") || text.contains("•") || text.contains("-")) {
                keyPoints.add(text.take(100))
            }
        }

        return keyPoints.take(5)
    }

    private fun generateTitle(messages: List<ChatMessage>): String {
        // أول رسالة من اليوزر غالباً بتحدد موضوع المحادثة
        val firstUserMsg = messages.firstOrNull { it.isUser }?.text ?: "New Chat"
        return firstUserMsg.take(40).ifBlank { "New Chat" }
    }

    private fun extractTags(messages: List<ChatMessage>): List<String> {
        val tags = mutableSetOf<String>()

        val categoryKeywords = mapOf(
            "phone_control" to listOf("open", "call", "send", "set alarm", "set timer"),
            "information" to listOf("what", "tell me", "explain", "define"),
            "productivity" to listOf("calendar", "reminder", "note", "contact"),
            "media" to listOf("play", "pause", "music", "track"),
            "settings" to listOf("wifi", "bluetooth", "brightness", "volume"),
            "navigation" to listOf("navigate", "directions", "maps"),
            "desktop" to listOf("desktop", "laptop", "computer")
        )

        messages.filter { it.isUser }.forEach { msg ->
            val lowerMsg = msg.text.lowercase()
            categoryKeywords.forEach { (category, keywords) ->
                if (keywords.any { kw -> lowerMsg.contains(kw) }) {
                    tags.add(category)
                }
            }
        }

        return tags.toList()
    }

    private fun extractKeywords(question: String): List<String> {
        // إزالة كلمات التوقف الشائعة
        val stopWords = setOf("the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "must", "shall", "can", "need", "dare",
            "ought", "used", "to", "of", "in", "for", "on", "with", "at", "by",
            "from", "as", "into", "through", "during", "before", "after", "above",
            "below", "between", "under", "again", "further", "then", "once",
            "what", "how", "why", "when", "where", "who", "which", "whom", "whose")

        return question.split(" ", ".", ",", "?", "!")
            .map { it.trim().lowercase() }
            .filter { it.length > 3 && !stopWords.contains(it) }
            .distinct()
            .take(10)
    }
}