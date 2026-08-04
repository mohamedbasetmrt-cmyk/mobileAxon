// TtsEngine.kt
package com.example.app_abdelbaset

interface TtsEngine {
    fun init(): Boolean
    fun speak(text: String, isLast: Boolean, onDone: () -> Unit)
    fun queueSentence(text: String, isLast: Boolean, onDone: () -> Unit)
    fun markEndOfStream(onAllDone: () -> Unit)
    fun stop()
    fun release()
    val isSpeaking: Boolean
    val isReady: Boolean
}