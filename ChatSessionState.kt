package com.example.app_abdelbaset

/**
 * ChatSessionState — يحتفظ بآخر session في الـ memory
 * لما اليوزر يخرج من ChatScreen ويرجع، الـ messages لسه موجودة.
 * يتمسح بس لما اليوزر يضغط New Chat أو الـ app يتقفل.
 */
object ChatSessionState {
    var messages: List<ChatMessage> = emptyList()
        private set
    var sessionId: String = ""
        private set

    fun save(msgs: List<ChatMessage>, id: String) {
        messages = msgs.filter { !it.isTyping }
        sessionId = id
    }

    fun clear() {
        messages = emptyList()
        sessionId = ""
    }

    fun hasSession(): Boolean = messages.isNotEmpty()
}