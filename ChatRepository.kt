package com.example.app_abdelbaset

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class ChatSession(
    val id: String,
    val title: String,
    val messages: List<ChatMessage>,
    val createdAt: Long
)

object ChatRepository {

    fun fetchSessions(
        endpoint: String,
        context: Context
    ): List<ChatSession> {
        return try {
            val url = java.net.URL("https://$endpoint/mobile/chats")
            val conn = url.openConnection() as java.net.HttpURLConnection

            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.requestMethod = "GET"

            val code = conn.responseCode

            if (code != 200) {
                conn.disconnect()
                return emptyList()
            }

            val body = conn.inputStream
                .bufferedReader()
                .readText()

            conn.disconnect()

            parseSessionList(body, context)

        } catch (e: Exception) {
            Log.e("ChatRepository", "Failed to fetch sessions", e)
            emptyList()
        }
    }

    // يرجع الـ node_id الجديد، أو "" لو فشل
    fun saveSession(
        endpoint: String,
        messages: List<ChatMessage>
    ): String {

        if (messages.isEmpty()) return ""

        return try {
            val title =
                messages
                    .firstOrNull { it.isUser }
                    ?.text
                    ?.take(40)
                    ?: "New Chat"

            val bodyJson = buildPostBody(title, messages)

            val url = java.net.URL(
                "https://$endpoint/mobile/chats"
            )

            val conn =
                url.openConnection() as java.net.HttpURLConnection

            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.requestMethod = "POST"
            conn.doOutput = true

            conn.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            conn.outputStream.use {
                it.write(bodyJson.toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode

            val resp =
                if (code in 200..299) {
                    conn.inputStream
                        .bufferedReader()
                        .readText()
                } else {
                    ""
                }

            conn.disconnect()

            if (code == 201) {
                JSONObject(resp)
                    .optInt("id", -1)
                    .takeIf { it != -1 }
                    ?.toString()
                    ?: ""
            } else {
                ""
            }

        } catch (e: Exception) {
            Log.e("ChatRepository", "Failed to save session", e)
            ""
        }
    }

    fun updateSession(
        endpoint: String,
        nodeId: String,
        messages: List<ChatMessage>
    ) {

        if (nodeId.isEmpty()) return

        try {
            val bodyJson = buildPutBody(messages)

            val url = java.net.URL(
                "https://$endpoint/mobile/chats/$nodeId"
            )

            val conn =
                url.openConnection() as java.net.HttpURLConnection

            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.requestMethod = "PUT"
            conn.doOutput = true

            conn.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            conn.outputStream.use {
                it.write(bodyJson.toByteArray(Charsets.UTF_8))
            }

            // لازم تقرأ response code عشان يتم إرسال الـ request
            conn.responseCode

            conn.disconnect()

        } catch (e: Exception) {
            Log.e("ChatRepository", "Failed to update session", e)
        }
    }

    fun deleteSession(
        endpoint: String,
        nodeId: String
    ): Boolean {

        return try {
            val url = java.net.URL(
                "https://$endpoint/mobile/chats/$nodeId"
            )

            val conn =
                url.openConnection() as java.net.HttpURLConnection

            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.requestMethod = "DELETE"

            val code = conn.responseCode

            conn.disconnect()

            code == 200

        } catch (e: Exception) {
            Log.e("ChatRepository", "Failed to delete session", e)
            false
        }
    }

    fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat(
            "MMM dd, HH:mm",
            Locale.getDefault()
        )

        return sdf.format(Date(timestamp))
    }

    // ─────────────────────────────────────────────────────────────
    // TXT EXPORT
    // ─────────────────────────────────────────────────────────────

    fun buildTxtContent(session: ChatSession): String {

        return buildString {

            appendLine("Chat: ${session.title}")
            appendLine("Date: ${formatDate(session.createdAt)}")
            appendLine()

            appendLine("=".repeat(50))
            appendLine()

            session.messages
                .filter { !it.isTyping }
                .forEach { message ->

                    appendLine(
                        if (message.isUser) {
                            "User:"
                        } else {
                            "Assistant:"
                        }
                    )

                    appendLine(message.text)
                    appendLine()

                    appendLine("-".repeat(50))
                    appendLine()
                }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private fun buildPostBody(
        title: String,
        messages: List<ChatMessage>
    ): String {

        val msgsArr = JSONArray()

        messages
            .filter { !it.isTyping }
            .forEach { message ->

                msgsArr.put(
                    JSONObject().apply {

                        put(
                            "role",
                            if (message.isUser) {
                                "user"
                            } else {
                                "assistant"
                            }
                        )

                        put(
                            "text",
                            message.text
                        )

                        put(
                            "timestamp",
                            JSONObject.NULL
                        )

                        put(
                            "metadata",
                            buildMetadata(message)
                        )
                    }
                )
            }

        return JSONObject().apply {

            put(
                "title",
                title
            )

            put(
                "messages",
                msgsArr
            )

        }.toString()
    }

    private fun buildPutBody(
        messages: List<ChatMessage>
    ): String {

        val msgsArr = JSONArray()

        messages
            .filter { !it.isTyping }
            .forEach { message ->

                msgsArr.put(
                    JSONObject().apply {

                        put(
                            "role",
                            if (message.isUser) {
                                "user"
                            } else {
                                "assistant"
                            }
                        )

                        put(
                            "text",
                            message.text
                        )

                        put(
                            "timestamp",
                            JSONObject.NULL
                        )

                        put(
                            "metadata",
                            buildMetadata(message)
                        )
                    }
                )
            }

        return JSONObject().apply {

            put(
                "messages",
                msgsArr
            )

        }.toString()
    }

    private fun buildMetadata(message: ChatMessage): JSONObject {
        val meta = JSONObject()
        if (message.references.isNotEmpty()) {
            val refsArr = JSONArray()
            message.references.forEach { ref ->
                refsArr.put(JSONObject().apply {
                    put("title", ref.title)
                    put("url", ref.url)
                    put("description", ref.description)
                })
            }
            meta.put("references", refsArr)
        }
        return meta
    }

    private fun parseSessionList(
        json: String,
        context: Context
    ): List<ChatSession> {

        return try {

            val arr = JSONArray(json)

            val list = mutableListOf<ChatSession>()

            for (i in 0 until arr.length()) {

                val obj = arr.getJSONObject(i)

                val nodeId =
                    obj.getInt("id").toString()

                val title =
                    obj.getString("title")

                val createdAt =
                    parseDate(
                        obj.getString("created_at")
                    )

                val content =
                    obj.optString(
                        "content",
                        "[]"
                    )

                val messages =
                    parseMessages(
                        content,
                        context
                    )

                list.add(
                    ChatSession(
                        id = nodeId,
                        title = title,
                        messages = messages,
                        createdAt = createdAt
                    )
                )
            }

            // ترتيب الجلسات من الأحدث للأقدم
            list.sortedByDescending {
                it.createdAt
            }

        } catch (e: Exception) {

            Log.w(
                "ChatRepository",
                "Error fetching remote sessions: ${e.message}, falling back to local"
            )

            ChatSummaryManager
                .getAllSummaries()
                .map { summary ->

                    ChatSession(
                        id = summary.sessionId,
                        title = summary.title,
                        messages =
                            ChatSummaryManager
                                .getFullSession(
                                    summary.sessionId
                                )
                                ?: emptyList(),
                        createdAt = summary.createdAt
                    )
                }
                .sortedByDescending {
                    it.createdAt
                }
        }
    }

    private fun parseMessages(
        contentJson: String,
        context: Context
    ): List<ChatMessage> {

        return try {

            val arr = JSONArray(contentJson)

            val msgs =
                mutableListOf<ChatMessage>()

            for (i in 0 until arr.length()) {

                val m =
                    arr.getJSONObject(i)

                val role =
                    m.optString(
                        "role",
                        "user"
                    )

                val text =
                    m.optString(
                        "text",
                        ""
                    )

                // الصور مش بتتحفظ على السيرفر
                // لذلك بنتجاهلها هنا

                // استرجاع المراجع من الـ metadata
                val references =
                    m.optJSONObject("metadata")
                        ?.optJSONArray("references")
                        ?.let { refsArr ->
                            List(refsArr.length()) { j ->
                                val ref = refsArr.getJSONObject(j)
                                AiReference(
                                    title = ref.optString("title", ""),
                                    url = ref.optString("url", ""),
                                    description = ref.optString("description", "")
                                )
                            }
                        }
                        ?: emptyList()

                msgs.add(
                    ChatMessage(
                        text = text,
                        isUser = role == "user",
                        references = references
                    )
                )
            }

            msgs

        } catch (e: Exception) {

            Log.e(
                "ChatRepository",
                "Failed to parse messages",
                e
            )

            emptyList()
        }
    }

    private fun parseDate(
        dateStr: String
    ): Long {

        return try {

            SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()
            )
                .parse(dateStr)
                ?.time
                ?: System.currentTimeMillis()

        } catch (e: Exception) {

            System.currentTimeMillis()
        }
    }
}