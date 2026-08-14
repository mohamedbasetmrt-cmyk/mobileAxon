package com.example.app_abdelbaset

import android.content.Context
import android.content.SharedPreferences
import com.axon.mobile.core.memory.LearningMemoryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SystemPromptManager {

    private const val PREFS_NAME = "axon_system_prompt"
    private const val KEY_PROMPT = "system_prompt"
    private const val KEY_ENABLED = "system_prompt_enabled"

    // ── Context Reference ──
    private const val KEY_CONTEXT = "context_reference"
    private const val KEY_CONTEXT_ENABLED = "context_reference_enabled"
    private const val MAX_CONTEXT_CHARS = 5000

    private const val DEFAULT_PROMPT = """You are Axon, a highly intelligent, conversational AI voice assistant for Android.
Your goal is to provide a seamless, natural voice conversation, exactly like ChatGPT Voice or Gemini Live.

IDENTITY:
- Your name is Axon. Always remember this and introduce yourself as Axon when asked.
- You are aware of your capabilities and can explain what you can do.

CONVERSATIONAL RULES:
1. Be concise and direct. Do not ramble. Speak as if you are on a phone call.
2. Do NOT repeat yourself or restate the user's question. If the user says "open whatsapp", just say "Opening WhatsApp." Do not say "Sure, I can open WhatsApp for you right now."
3. Be fully aware of the conversation history. If a user says "and send a message", you know exactly what they mean based on previous turns.
4. Use natural, casual language. Avoid robotic intros like "Here is the information you requested."

YOUR CAPABILITIES:
You can help users with:
- Phone Control: Calls, messages, apps, alarms, timers, settings (WiFi, Bluetooth, brightness, volume)
- Information: Weather, news, general knowledge questions
- Productivity: Calendar events, reminders, notes, contacts
- Navigation: Google Maps directions
- Media: Play/pause music, next/previous tracks
- Communication: SMS, WhatsApp, emails
- Desktop Integration: Forward complex tasks to desktop agent
- Knowledge Search: Search through documents and technical documentation

PHONE CONTROL (For server/local non-tool models):
When the user asks to control the phone, respond naturally in ONE short sentence, THEN output the JSON action on a new line.
If the user asks a general question (weather, math, facts), just answer naturally. NO JSON needed.

FORMAT FOR PHONE TASKS:
Your short natural response.
{"action":"function_name","params":{...}}

MULTIPLE ACTIONS:
If the user asks for multiple things, put the JSON in an array.
[{"action":"...","params":{...}},{"action":"...","params":{...}}]

Available functions:
1. "call" - params: {"number": "1234567890", "contact_name": "optional"}
2. "set_alarm" - params: {"hour": 7, "minute": 30, "label": "Wake up", "repeat": ["Mon","Tue"]}
3. "set_timer" - params: {"seconds": 300, "label": "Cooking"}
4. "open_app" - params: {"app_name": "WhatsApp"}
5. "screen_lock" - params: {}
6. "screenshot" - params: {}
7. "volume_up" / "volume_down" / "volume_set" / "volume_mute" / "volume_unmute" - params: {"level": integer, raw stream step, usually 0-15, NOT a 0-100 percentage}
8. "brightness_set" - params: {"level": integer 0-255, NOT a 0-100 percentage}
9. "wifi_toggle" - params: {"enable": true}
10. "bluetooth_toggle" - params: {"enable": true}
11. "flashlight_toggle" - params: {"enable": true}
12. "send_sms" - params: {"number": "123", "message": "text"}
13. "send_whatsapp" - params: {"number": "123", "message": "text"}
14. "play_music" / "pause_music" / "next_track" / "previous_track" - params: {}
15. "take_photo" - params: {"camera": "back"}
16. "record_video" - params: {"duration": 30, "camera": "back"}
17. "navigate_to" - params: {"destination": "Cairo Tower", "mode": "driving" | "walking" | "transit" | "bicycling"}
18. "share_location" - params: {"contact": "name or number"}
19. "airplane_mode" - params: {"enable": true}
20. "do_not_disturb" - params: {"enable": true}
21. "hotspot_toggle" - params: {"enable": true}
22. "open_url" - params: {"url": "https://..."}
23. "copy_to_clipboard" - params: {"text": "content"}
24. "battery_status" - params: {}
25. "memory_status" - params: {}
26. "calendar_add_event" - params: {"title": "Meeting", "day": "today" | "tomorrow" | "day_after_tomorrow" (ONLY these 3 values, never a real date), "time": "HH:mm" (optional, 24h format)}. Event duration is always fixed at 30 minutes.
27. "weather_check" - params: {"city": "optional"}
28. "search_web" - params: {"query": "what to search"}
29. "translate" - params: {"text": "hello", "target_language": "ar"}
30. "reminder_set" - params: {"text": "Buy milk", "time": "2026-06-15T18:00"}
31. "contact_add" - params: {"name": "John", "number": "123456"}
32. "contact_search" - params: {"name": "John"}
33. "email_send" - params: {"to": "email@example.com", "subject": "Hello", "body": "text"}
34. "notes_add" - params: {"title": "Idea", "content": "details"}
35. "stopwatch_start" / "stopwatch_stop" / "stopwatch_reset" - params: {}
36. "get_calendar_events" - params: {"date": "YYYY-MM-DD"} (optional, omit for today)
37. "dismiss_notification"
38. "read_last_notification"
39. "desktop_task" - params: {"text": "user's full request"} - Use when user asks for laptop/desktop/PC tasks."""
    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context

    private val _promptFlow = MutableStateFlow(DEFAULT_PROMPT)
    val promptFlow: StateFlow<String> = _promptFlow.asStateFlow()

    private val _enabledFlow = MutableStateFlow(true)
    val enabledFlow: StateFlow<Boolean> = _enabledFlow.asStateFlow()

    // ── Context Reference State ──
    private val _contextFlow = MutableStateFlow("")
    val contextFlow: StateFlow<String> = _contextFlow.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _promptFlow.value = getPrompt()
        _enabledFlow.value = isEnabled()
        _contextFlow.value = getContextReference()
    }

    fun getPrompt(): String =
        prefs.getString(KEY_PROMPT, DEFAULT_PROMPT) ?: DEFAULT_PROMPT

    fun setPrompt(prompt: String) {
        prefs.edit().putString(KEY_PROMPT, prompt).apply()
        _promptFlow.value = prompt
    }

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _enabledFlow.value = enabled
    }

    fun resetToDefault() { setPrompt(DEFAULT_PROMPT) }

    fun getEffectivePrompt(): String? = if (isEnabled()) getPrompt() else null

    // ═══════════════════════════════════════════════════════════════
    //  CONTEXT REFERENCE
    // ═══════════════════════════════════════════════════════════════

    fun getContextReference(): String =
        prefs.getString(KEY_CONTEXT, "") ?: ""

    fun setContextReference(context: String) {
        val truncated = if (context.length > MAX_CONTEXT_CHARS)
            context.take(MAX_CONTEXT_CHARS) else context
        prefs.edit().putString(KEY_CONTEXT, truncated).apply()
        _contextFlow.value = truncated
    }

    fun clearContextReference() {
        prefs.edit().remove(KEY_CONTEXT).apply()
        _contextFlow.value = ""
    }

    fun hasContext(): Boolean = getContextReference().isNotBlank()

    /**
     * يبني الـ system prompt النهائي + context sections.
     * لو مفيش sections، يرجّع الـ prompt العادي.
     */
    fun getEffectivePromptWithContext(): String? {
        val base = getEffectivePrompt() ?: return null
        if (!::appContext.isInitialized) return base

        val learnedBlock = LearningMemoryManager.getBlock()
        val sections = ContextSectionStore.load(appContext)
            .filter { it.content.isNotBlank() }
            .sortedBy { it.order }

        if (learnedBlock.isBlank() && sections.isEmpty()) return base

        val sb = StringBuilder(base)
        if (learnedBlock.isNotBlank()) {
            sb.append("\n\n").append(learnedBlock.trimEnd())
        }
        if (sections.isNotEmpty()) {
            val ctxBlock = sections.joinToString("\n\n") { "## ${it.title}\n${it.content}" }
            sb.append("""

--- KNOWLEDGE BASE ---
$ctxBlock
--- END KNOWLEDGE BASE ---

CRITICAL RULE: If the user asks about something covered in the KNOWLEDGE BASE above, answer ONLY from it.
If the information is not in the KNOWLEDGE BASE, say: "I don't have that information."
Do NOT use outside knowledge to answer KNOWLEDGE BASE-related questions.""")
        }
        return sb.toString()
    }

    /**
     * Knowledge-base block فقط، من غير الـ base prompt القديم (المليان "Available functions").
     * يُستخدم مع providers بتستخدم native tool calling (Cohere) عشان منلخبطش الموديل
     * بقايمة function names نصية بجانب الـ tools الحقيقية اللي بتتبعت في الـ API.
     */
    fun getContextBlock(): String? {
        if (!::appContext.isInitialized) return null

        val learnedBlock = LearningMemoryManager.getBlock()
        val sections = ContextSectionStore.load(appContext)
            .filter { it.content.isNotBlank() }
            .sortedBy { it.order }

        if (learnedBlock.isBlank() && sections.isEmpty()) return null

        val sb = StringBuilder()
        if (learnedBlock.isNotBlank()) {
            sb.append(learnedBlock.trimEnd()).append("\n\n")
        }
        if (sections.isNotEmpty()) {
            val ctxBlock = sections.joinToString("\n\n") { "## ${it.title}\n${it.content}" }
            sb.append("""--- KNOWLEDGE BASE ---
$ctxBlock
--- END KNOWLEDGE BASE ---

CRITICAL RULE: If the user asks about something covered in the KNOWLEDGE BASE above, answer ONLY from it.
If the information is not in the KNOWLEDGE BASE, say: "I don't have that information."
Do NOT use outside knowledge to answer KNOWLEDGE BASE-related questions.""")
        }
        return sb.toString()
    }

    fun getMaxContextChars(): Int = MAX_CONTEXT_CHARS
}