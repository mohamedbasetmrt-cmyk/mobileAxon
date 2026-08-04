package com.example.app_abdelbaset

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages AI interactions after wake word detection.
 * NOTE: With the new server pipeline, this class is no longer used for
 * the main voice flow (AxonVoiceSession handles that). It is kept here
 * for local/fallback processing if needed.
 */
class AiManager(
    private val context: Context,
    private val onResponse: (String) -> Unit,
    private val onError: (Exception) -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "AiManager"
        private const val API_ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val API_KEY = "YOUR_ANTHROPIC_API_KEY_HERE"
    }

    fun processText(text: String) {
        scope.launch {
            try {
                Log.d(TAG, "Processing text: $text")
                val response = sendToClaudeAPI(text)
                withContext(Dispatchers.Main) { onResponse(response) }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing text", e)
                withContext(Dispatchers.Main) { onError(e) }
            }
        }
    }

    private suspend fun sendToClaudeAPI(userMessage: String): String = withContext(Dispatchers.IO) {
        val url = URL(API_ENDPOINT)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("x-api-key", API_KEY)
            connection.setRequestProperty("anthropic-version", "2023-06-01")
            connection.doOutput = true
            connection.doInput = true

            val messagesArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage)
                })
            }

            val requestBody = JSONObject().apply {
                put("model", "claude-3-5-sonnet-20241022")
                put("max_tokens", 1024)
                put("messages", messagesArray)
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream))
                    .use { it.readText() }
                val jsonResponse = JSONObject(response)
                val contentArray = jsonResponse.getJSONArray("content")
                if (contentArray.length() > 0) {
                    return@withContext contentArray.getJSONObject(0).getString("text")
                }
                return@withContext "No response from AI"
            } else {
                val errorResponse = BufferedReader(InputStreamReader(connection.errorStream))
                    .use { it.readText() }
                throw Exception("API Error: $responseCode - $errorResponse")
            }
        } finally {
            connection.disconnect()
        }
    }

    fun processLocally(text: String) {
        scope.launch {
            delay(500)
            val response = when {
                text.contains("hello", ignoreCase = true)          -> "Hello! How can I help you?"
                text.contains("time", ignoreCase = true)           -> "I don't have clock access right now."
                text.contains("weather", ignoreCase = true)        -> "I don't have access to weather data yet."
                text.contains("how are you", ignoreCase = true)    -> "I'm doing great, thank you!"
                text.contains("what's your name", ignoreCase = true) -> "I'm Axon, your voice assistant."
                text.contains("thank", ignoreCase = true)          -> "You're welcome!"
                text.contains("bye", ignoreCase = true)            -> "Goodbye! Say 'Axon' to call me again."
                else -> "I heard: $text"
            }
            withContext(Dispatchers.Main) { onResponse(response) }
        }
    }

    fun processAudio(audioData: ByteArray) {
        // Reserved for future direct audio API support
    }

    fun cancel() {
        scope.cancel()
    }
}