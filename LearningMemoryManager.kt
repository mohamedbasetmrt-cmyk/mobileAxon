package com.axon.mobile.core.memory

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * LearningMemoryManager: Stores user preferences, prohibitions, and facts
 * extracted from conversation to be injected into system prompts.
 */
object LearningMemoryManager {

    private const val PREFS_NAME = "axon_learning_memory"
    private const val KEY_ENTRIES = "learned_entries"
    private const val MAX_ENTRIES = 30
    private const val MAX_CHARS_PER_ENTRY = 150

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    data class MemoryEntry(
        val id: String,
        val text: String,
        val type: Type,
        val timestamp: Long
    ) {
        enum class Type { PROHIBITION, PREFERENCE, FACT }
    }

    /**
     * Extracts patterns from user text and stores them if valid.
     */
    fun extractAndStore(userText: String) {
        val normalized = userText.trim()
        if (normalized.length < 5 || normalized.length > MAX_CHARS_PER_ENTRY) return

        val type = detectType(normalized) ?: return
        
        // Simple deduplication check
        val existing = getAll()
        if (existing.any { it.text.equals(normalized, ignoreCase = true) }) return

        val entry = MemoryEntry(
            id = System.currentTimeMillis().toString(),
            text = normalized,
            type = type,
            timestamp = System.currentTimeMillis()
        )

        saveEntry(entry)
    }

    private fun detectType(text: String): MemoryEntry.Type? {
        val lower = text.lowercase()
        
        // Prohibitions (Don't, Never, Stop, Mat..., La...)
        val prohibitionPatterns = listOf(
            "don't", "never", "stop", "no more", "quit",
            "مت", "لا", "مات", "بطل", "وقفي", "من غير ما", "لا تحاول", "كفاية"
        )
        if (prohibitionPatterns.any { lower.contains(it) }) {
            return MemoryEntry.Type.PROHIBITION
        }

        // Preferences (Prefer, Like, Want, Afdal...)
        val preferencePatterns = listOf(
            "i prefer", "i like", "i want you", "always", "please",
            "أفضل", "بحب", "عايزك", "دايما", "دائما", "من فضلك", "لو سمحت"
        )
        if (preferencePatterns.any { lower.contains(it) }) {
            return MemoryEntry.Type.PREFERENCE
        }

        // Facts (My name is, Call me, I live in...)
        val factPatterns = listOf(
            "my name is", "call me", "i live in", "i am", "i work",
            "اسمي", "ناديني", "انا اسمي", "عندي", "رقمي", "ساكن في"
        )
        if (factPatterns.any { lower.contains(it) }) {
            return MemoryEntry.Type.FACT
        }

        return null
    }

    private fun saveEntry(entry: MemoryEntry) {
        val entries = getAll().toMutableList()
        
        // Enforce limit
        while (entries.size >= MAX_ENTRIES) {
            entries.removeAt(0) // Remove oldest
        }
        
        entries.add(entry)
        
        val jsonArray = JSONArray()
        entries.forEach { e ->
            JSONObject().apply {
                put("id", e.id)
                put("text", e.text)
                put("type", e.type.name)
                put("timestamp", e.timestamp)
            }.let { jsonArray.put(it) }
        }
        
        prefs.edit().putString(KEY_ENTRIES, jsonArray.toString()).apply()
    }

    fun getAll(): List<MemoryEntry> {
        val jsonStr = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        val jsonArray = JSONArray(jsonStr)
        val list = mutableListOf<MemoryEntry>()
        
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            list.add(
                MemoryEntry(
                    id = obj.getString("id"),
                    text = obj.getString("text"),
                    type = MemoryEntry.Type.valueOf(obj.getString("type")),
                    timestamp = obj.getLong("timestamp")
                )
            )
        }
        return list
    }

    fun clear() {
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    /**
     * Generates the block to be injected into the System Prompt.
     */
    fun getBlock(): String {
        val entries = getAll()
        if (entries.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("--- LEARNED USER PREFERENCES (follow these strictly) ---")
        entries.forEach { entry ->
            // Format based on type for clarity to the LLM
            val prefix = when(entry.type) {
                MemoryEntry.Type.PROHIBITION -> "[RULE] "
                MemoryEntry.Type.PREFERENCE -> "[PREF] "
                MemoryEntry.Type.FACT -> "[FACT] "
            }
            sb.appendLine("$prefix${entry.text}")
        }
        sb.appendLine("--- END LEARNED PREFERENCES ---")
        return sb.toString()
    }
}
