package com.example.app_abdelbaset

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

// Data classes for tracking
data class ToolUsageRecord(
    val timestamp: Long,
    val toolName: String,
    val parameters: String
)

data class ConversationSummary(
    val timestamp: Long,
    val sessionId: String,
    val messageCount: Int,
    val summary: String
)

object ServiceStatsTracker {
    var queryCount    by mutableStateOf(0)
    var avgResponseMs by mutableStateOf(0L)
    var isOnline      by mutableStateOf(false)

    // ── NEW: Use State lists for real-time UI updates ──
    var lastSystemPrompt by mutableStateOf("")
    var lastPromptText by mutableStateOf("")
    val toolUsageHistory = mutableStateListOf<ToolUsageRecord>()
    val conversationSummaries = mutableStateListOf<ConversationSummary>()

    private var totalResponseMs = 0L
    private var responseCount   = 0
    private var sessionStart    = 0L

    fun onServiceStarted() {
        isOnline     = true
        sessionStart = System.currentTimeMillis()
    }

    fun onServiceStopped() {
        isOnline = false
    }

    fun onQueryStarted() {
        sessionStart = System.currentTimeMillis()
    }

    fun onQueryFinished() {
        val elapsed = System.currentTimeMillis() - sessionStart
        queryCount++
        totalResponseMs += elapsed
        responseCount++
        avgResponseMs = totalResponseMs / responseCount
    }

    fun recordPrompts(systemPrompt: String, userPrompt: String) {
        lastSystemPrompt = systemPrompt
        lastPromptText = userPrompt
    }

    fun recordToolUsage(toolName: String, parameters: String) {
        val record = ToolUsageRecord(
            timestamp = System.currentTimeMillis(),
            toolName = toolName,
            parameters = parameters
        )
        toolUsageHistory.add(0, record) // Add to top
        if (toolUsageHistory.size > 20) toolUsageHistory.removeAt(20) // Keep last 20
    }

    fun recordConversationSummary(sessionId: String, messageCount: Int, summary: String) {
        val summaryRecord = ConversationSummary(
            timestamp = System.currentTimeMillis(),
            sessionId = sessionId,
            messageCount = messageCount,
            summary = summary
        )
        conversationSummaries.add(0, summaryRecord) // Add to top
        if (conversationSummaries.size > 20) conversationSummaries.removeAt(20) // Keep last 20
    }

    fun avgResponseString(): String {
        return if (avgResponseMs == 0L) "—"
        else if (avgResponseMs < 1000) "${avgResponseMs}ms"
        else "${"%.1f".format(avgResponseMs / 1000.0)}s"
    }

    fun reset() {
        queryCount      = 0
        avgResponseMs   = 0L
        totalResponseMs = 0L
        responseCount   = 0
        isOnline        = false
        lastSystemPrompt = ""
        lastPromptText = ""
        toolUsageHistory.clear()
        conversationSummaries.clear()
    }
}