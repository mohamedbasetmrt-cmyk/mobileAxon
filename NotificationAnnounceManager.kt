package com.example.app_abdelbaset

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Data class لقاعدة إشعار واحدة
 */
data class NotificationRule(
    val packageName: String,
    val appLabel: String,
    val enabled: Boolean = true,
    val template: String = "you have notification from {app}: {title}"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("appLabel", appLabel)
        put("enabled", enabled)
        put("template", template)
    }

    companion object {
        fun fromJson(json: JSONObject): NotificationRule = NotificationRule(
            packageName = json.optString("packageName", ""),
            appLabel = json.optString("appLabel", ""),
            enabled = json.optBoolean("enabled", true),
            template = json.optString("template", "you have notification from {app}: {title}")
        )
    }
}

/**
 * Singleton — يدير قواعد الإعلان الصوتي للإشعارات
 * يخزن Rules في SharedPreferences كـ JSON Array
 */
object NotificationAnnounceManager {

    private const val TAG = "NotifAnnounceMgr"
    private const val PREFS_NAME = "axon_notif_rules_prefs"
    private const val KEY_RULES = "axon_notif_rules"
    private const val KEY_MASTER = "axon_notif_master_enabled"

    private val rules = mutableListOf<NotificationRule>()
    private var masterEnabled = true
    private var initialized = false

    // ── Init ────────────────────────────────────────────────────────
    fun init(context: Context) {
        if (initialized) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        masterEnabled = prefs.getBoolean(KEY_MASTER, true)

        val jsonStr = prefs.getString(KEY_RULES, null)
        if (jsonStr != null) {
            try {
                val arr = JSONArray(jsonStr)
                for (i in 0 until arr.length()) {
                    rules.add(NotificationRule.fromJson(arr.getJSONObject(i)))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse rules", e)
            }
        }
        initialized = true
        Log.d(TAG, "Initialized with ${rules.size} rules, master=$masterEnabled")
    }

    /** يضمن التهيئة لو الـ Service بدأ قبل الـ Activity */
    fun ensureInitialized(context: Context) {
        if (!initialized) init(context)
    }

    // ── Read ────────────────────────────────────────────────────────
    fun getRules(): List<NotificationRule> = synchronized(rules) { rules.toList() }

    fun getRuleFor(packageName: String): NotificationRule? {
        return synchronized(rules) { rules.find { it.packageName == packageName } }
    }

    fun isMasterEnabled(): Boolean = masterEnabled

    // ── Write ───────────────────────────────────────────────────────
    fun addOrUpdateRule(rule: NotificationRule, context: Context) {
        synchronized(rules) {
            val idx = rules.indexOfFirst { it.packageName == rule.packageName }
            if (idx >= 0) rules[idx] = rule else rules.add(rule)
        }
        persist(context)
        Log.d(TAG, "Rule saved for ${rule.packageName}")
    }

    fun removeRule(packageName: String, context: Context) {
        synchronized(rules) { rules.removeAll { it.packageName == packageName } }
        persist(context)
        Log.d(TAG, "Rule removed for $packageName")
    }

    fun setMasterEnabled(enabled: Boolean, context: Context) {
        masterEnabled = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MASTER, enabled).apply()
        Log.d(TAG, "Master enabled=$enabled")
    }

    // ── Template Builder ────────────────────────────────────────────
    fun buildSpeechText(
        rule: NotificationRule,
        title: String,
        content: String,
        appLabel: String
    ): String {
        return rule.template
            .replace("{app}", appLabel.ifBlank { rule.appLabel })
            .replace("{title}", title)
            .replace("{content}", content)
    }

    /** يبني نص تجريبي للمعاينة الحية في الـ Dialog */
    fun buildPreviewText(template: String): String {
        return template
            .replace("{app}", "WhatsApp")
            .replace("{title}", "Test Title")
            .replace("{content}", "Test Content")
    }

    // ── Persist ─────────────────────────────────────────────────────
    private fun persist(context: Context) {
        val arr = JSONArray()
        synchronized(rules) { rules.forEach { arr.put(it.toJson()) } }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_RULES, arr.toString()).apply()
    }
}