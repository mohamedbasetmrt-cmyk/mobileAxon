package com.example.app_abdelbaset

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object ServiceStatsTracker {
    var queryCount    by mutableStateOf(0)
    var avgResponseMs by mutableStateOf(0L)
    var isOnline      by mutableStateOf(false)

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
    }
}