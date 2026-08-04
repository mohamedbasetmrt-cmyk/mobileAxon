package com.example.app_abdelbaset

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object KnowledgeSearchUtil {

    private const val TAG = "KnowledgeSearch"
    private const val ENDPOINT = "https://1mr-trackfacehf.hf.space/search"

    // 🔑 المفتاح اللي بيبعته للسيرفر
    private const val API_SECRET_KEY = "dRJ3fdzXJSrYRWjsEXvwwzl9g8JNBn0b"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    suspend fun search(query: String): String {
        if (query.isBlank()) return "Empty search query."

        return try {
            Log.d(TAG, "Searching knowledge base: $query")

            val payload = JSONObject().apply {
                put("query", query)
                put("top_k", 5)
                put("hybrid", false)
                put("keyword_weight", 0.3)
                // اختياري: فعّل الـ web search والـ LLM synthesis
                // put("web_search", true)
                // put("synthesize", true)
            }

            val requestBody = payload.toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(ENDPOINT)
                .header("X-API-Key", API_SECRET_KEY)          // ✅ ده المفتاح
                // أو بدلها:
                // .header("Authorization", "Bearer $API_SECRET_KEY")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()

            if (response.code == 401) {
                Log.e(TAG, "Unauthorized: invalid or missing API key")
                return "Unauthorized: the server rejected the API key."
            }
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: "unknown"
                Log.e(TAG, "Search HTTP ${response.code}: $errBody")
                return "Search error: HTTP ${response.code}"
            }

            val body = response.body?.string() ?: return "Empty search response"
            val json = JSONObject(body)

            // ✅ لو السيرفر رجّع answer من الـ LLM، استخدمه أولًا
            val answer = json.optString("answer", "").trim()
            val contents = mutableListOf<String>()

            // لو فيه answer، ضيفه أول حاجة
            if (answer.isNotEmpty()) {
                contents.add(answer)
            }

            // اجمع الـ results من الـ local و web كمان
            val localResults = json.optJSONArray("local_results")
            if (localResults != null) {
                for (i in 0 until localResults.length()) {
                    val item = localResults.optJSONObject(i) ?: continue
                    val text = item.optString("content", "").trim()
                    if (text.isNotEmpty()) contents.add(text)
                }
            }

            val webResults = json.optJSONArray("web_results")
            if (webResults != null) {
                for (i in 0 until webResults.length()) {
                    val item = webResults.optJSONObject(i) ?: continue
                    val text = item.optString("content", "").trim()
                    if (text.isNotEmpty()) contents.add("[Web] $text")
                }
            }

            if (contents.isEmpty()) {
                "No relevant information found for: $query"
            } else {
                contents.joinToString("\n\n---\n\n")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Search failed", e)
            "Search failed: ${e.message}"
        }
    }
}