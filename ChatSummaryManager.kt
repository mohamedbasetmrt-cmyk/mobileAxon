package com.example.app_abdelbaset

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

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
    val tags: List<String> = emptyList()
)

object ChatSummaryManager {
    private const val TAG = "ChatSummaryManager"
    private const val SUMMARIES_DIR = "chat_summaries"
    private const val CHATS_DIR = "chat_sessions"

    private lateinit var appContext: Context
    private lateinit var summariesDir: File
    private lateinit var chatsDir: File

    fun init(context: Context) {
        appContext = context.applicationContext
        summariesDir = File(appContext.filesDir, SUMMARIES_DIR)
        chatsDir = File(appContext.filesDir, CHATS_DIR)

        if (!summariesDir.exists()) summariesDir.mkdirs()
        if (!chatsDir.exists()) chatsDir.mkdirs()
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

            // 2. إنشاء الملخص
            val summary = generateSummary(messages)
            val keyPoints = extractKeyPoints(messages)
            val title = generateTitle(messages)
            val tags = extractTags(messages)

            // 3. حفظ الملخص
            val summaryFile = File(summariesDir, "$sessionId.json")
            val summaryObj = JSONObject().apply {
                put("sessionId", sessionId)
                put("title", title)
                put("summary", summary)
                put("keyPoints", JSONArray(keyPoints))
                put("messageCount", messages.size)
                put("createdAt", System.currentTimeMillis())
                put("updatedAt", System.currentTimeMillis())
                put("tags", JSONArray(tags))
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
                }
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
                            }
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Error parsing ${file.name}: ${e.message}")
                        null
                    }
                }?.sortedByDescending { it.updatedAt } ?: emptyList()
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
                ChatMessage(
                    text = msg.getString("text"),
                    isUser = msg.getString("role") == "user"
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
        messages.forEach { m ->
            msgsArr.put(JSONObject().apply {
                put("role", if (m.isUser) "user" else "assistant")
                put("text", m.text)
                put("timestamp", System.currentTimeMillis())
            })
        }

        return JSONObject().apply {
            put("sessionId", sessionId)
            put("messages", msgsArr)
            put("createdAt", System.currentTimeMillis())
        }
    }

    private fun generateSummary(messages: List<ChatMessage>): String {
        if (messages.isEmpty()) return "Empty conversation"

        // استخراج الموضوعات الرئيسية
        val userMessages = messages.filter { it.isUser }.map { it.text }
        val assistantMessages = messages.filter { !it.isUser }.map { it.text }

        // تلخيص بسيط (في المستقبل ممكن نستخدم LLM أصغر للتوليد)
        val topics = mutableListOf<String>()

        // اكتشاف الأفعال والأوامر
        val actionKeywords = listOf("open", "call", "send", "set", "play", "search", "tell", "what", "how", "why")
        userMessages.forEach { msg ->
            actionKeywords.forEach { keyword ->
                if (msg.lowercase().contains(keyword) && topics.size < 3) {
                    topics.add(msg.take(80))
                }
            }
        }

        if (topics.isEmpty()) {
            topics.add("General conversation")
        }

        return "Discussion about: ${topics.joinToString(", ")}"
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