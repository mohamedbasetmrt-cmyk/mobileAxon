package com.example.app_abdelbaset

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

class AxonNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "AxonNotifListener"

        var lastNotification: String = ""
            private set

        // ── Data class بدل String الخام ────────────────────────────
        data class RecentNotifEntry(
            val packageName: String,
            val appName: String,
            val title: String,
            val content: String
        ) {
            fun toDisplayString(): String = "[$appName] $title: $content".trim()
        }

        // قائمة آخر 10 إشعارات (Object بدل String)
        val recentNotifications = ArrayDeque<RecentNotifEntry>(10)

        // الـ instance الحالي للخدمة
        @Volatile
        var instance: AxonNotificationListener? = null
            private set

        // ← Fallback: باكدجات تقويم معروفة (لو مفيش Rules)
        private val calendarPackages = setOf(
            "com.google.android.calendar",
            "com.samsung.android.calendar",
            "com.microsoft.office.outlook",
            "com.htc.calendar",
            "com.android.calendar"
        )

        // منع تكرار التنبيه الصوتي
        private val announcedKeys = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        // Executor بعيد عن main thread
        private val ttsExecutor: java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newSingleThreadExecutor()

        /** يرجع قائمة التطبيقات الفريدة اللي بعتت إشعارات مؤخرًا */
        fun getRecentUniquePackages(): List<Pair<String, String>> {
            return recentNotifications
                .map { it.packageName to it.appName }
                .distinctBy { it.first }
                .reversed()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // تهيئة الـ Manager احتياطيًا (لو الـ Service بدأ قبل الـ Activity)
        NotificationAnnounceManager.ensureInitialized(applicationContext)
        Log.d(TAG, "NotificationListenerService created")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
        Log.d(TAG, "NotificationListenerService destroyed")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Listener connected — can now read notifications")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val appName = getAppName(sbn.packageName)
        val extras  = sbn.notification?.extras ?: return

        val title   = extras.getCharSequence("android.title")?.toString() ?: ""
        val text    = extras.getCharSequence("android.text")?.toString()  ?: ""
        val bigText = extras.getCharSequence("android.bigText")?.toString() ?: ""
        val content = bigText.ifBlank { text }

        // ── تحديث lastNotification (String للتوافق) ───────────
        val displayString = "[$appName] $title: $content".trim()
        if (displayString.isNotBlank()) {
            lastNotification = displayString
        }

        // ── تحديث recentNotifications (Object) ─────────────────
        val entry = RecentNotifEntry(
            packageName = sbn.packageName,
            appName = appName,
            title = title,
            content = content
        )
        if (recentNotifications.size >= 10) recentNotifications.removeFirst()
        recentNotifications.addLast(entry)

        // ── التحقق من القواعد والإعلان الصوتي ─────────────────
        NotificationAnnounceManager.ensureInitialized(applicationContext)

        val rule = NotificationAnnounceManager.getRuleFor(sbn.packageName)
        val masterOn = NotificationAnnounceManager.isMasterEnabled()

        if (rule != null && rule.enabled && masterOn) {
            // يوجد Rule مخصصة لهذا التطبيق
            announceNotification(rule, title, content, appName, sbn.key)
        } else if (rule == null && masterOn) {
            // مفيش Rule — نستخدم الـ fallback للكاليندر
            val isCalendarApp = calendarPackages.contains(sbn.packageName)
                    || sbn.packageName.contains("calendar", ignoreCase = true)
            if (isCalendarApp) {
                val fallbackRule = NotificationRule(
                    packageName = sbn.packageName,
                    appLabel = appName,
                    template = "you have calendar about: {title}"
                )
                announceNotification(fallbackRule, title, content, appName, sbn.key)
            }
        }
    }

    /**
     * الإعلان الصوتي العام — يعمل ل أي Rule (مخصصة أو fallback)
     */
    private fun announceNotification(
        rule: NotificationRule,
        title: String,
        content: String,
        appLabel: String,
        notificationKey: String
    ) {
        // نص ثابت موحد: "There's a notification from {app}"
        val speechText = "There's a notification from $appLabel"
        if (speechText.isBlank()) return

        if (!announcedKeys.add(notificationKey)) return

        // منع التكرار
        val dedupKey = "${rule.packageName}|$title|$content"
        if (!announcedKeys.add(dedupKey)) return

        ttsExecutor.execute {
            var engine: TtsEngine? = null
            try {
                // Deepgram TTS لو في نت + API key، وإلا Local TTS
                engine = buildAnnounceEngine()
                if (engine.init()) {
                    val latch = java.util.concurrent.CountDownLatch(1)
                    engine.speak(speechText, isLast = true) { latch.countDown() }
                    latch.await(8, java.util.concurrent.TimeUnit.SECONDS)

                    // ── Fallback: Deepgram فشل (key غلط / quota / خطأ) → Local TTS ──
                    if (engine is DeepgramTtsEngine && engine.lastPlaybackFailed) {
                        Log.w(TAG, "Deepgram TTS failed, falling back to Local TTS")
                        engine.release()
                        engine = LocalTtsEngine(applicationContext)
                        if (engine.init()) {
                            val localLatch = java.util.concurrent.CountDownLatch(1)
                            engine.speak(speechText, isLast = true) { localLatch.countDown() }
                            localLatch.await(8, java.util.concurrent.TimeUnit.SECONDS)
                        }
                    }

                    Thread.sleep(3000)

                    try {
                        cancelNotification(notificationKey)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to dismiss notification: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Announce TTS error: ${e.message}")
            } finally {
                engine?.release()
            }
        }
    }

    /**
     * يختار محرك TTS للإعلان:
     * Deepgram (سحابي) لو فيه اتصال بالنت ومفتاح API موجود، وإلا Local TTS.
     */
    private fun buildAnnounceEngine(): TtsEngine {
        val prefs = getSharedPreferences("axon_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("deepgram_tts_api_key", "") ?: ""
        if (isNetworkConnected() && apiKey.isNotBlank()) {
            val voice = prefs.getString("deepgram_tts_voice", MainActivity.DEFAULT_DEEPGRAM_TTS_VOICE)
                ?: MainActivity.DEFAULT_DEEPGRAM_TTS_VOICE
            Log.d(TAG, "Announcement via Deepgram TTS")
            return DeepgramTtsEngine(applicationContext, apiKey, voice)
        }
        Log.d(TAG, "Announcement via Local TTS")
        return LocalTtsEngine(applicationContext)
    }

    /** فحص اتصال الإنترنت (يتطلب ACCESS_NETWORK_STATE في الـ Manifest) */
    private fun isNetworkConnected(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        Log.d(TAG, "Notification removed: ${sbn?.packageName}")
    }

    /**
     * استرجاع آخر إشعار من باقة معينة
     */
    fun getLastFor(packageFilter: String = ""): String {
        if (packageFilter.isBlank()) return lastNotification

        return recentNotifications
            .lastOrNull { it.packageName == packageFilter }
            ?.toDisplayString()
            ?: "No notifications from $packageFilter"
    }

    /**
     * مسح كل الإشعارات النشطة
     */
    fun dismissAll() {
        try {
            cancelAllNotifications()
            Log.d(TAG, "All notifications dismissed")
        } catch (e: Exception) {
            Log.e(TAG, "dismissAll error: ${e.message}")
        }
    }

    /**
     * مسح إشعار تطبيق معين
     */
    fun dismissFrom(packageName: String) {
        try {
            val active = activeNotifications ?: return
            active.filter { it.packageName == packageName }
                .forEach { cancelNotification(it.key) }
            Log.d(TAG, "Dismissed notifications from $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "dismissFrom error: ${e.message}")
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────
    private fun getAppName(packageName: String): String {
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    fun getAllNotifications(): String {
        return activeNotifications
            ?.joinToString("\n") { sbn ->
                val appName = getAppName(sbn.packageName)
                val extras  = sbn.notification.extras
                "[$appName] ${extras.getCharSequence("android.title")} — ${extras.getCharSequence("android.text")}"
            }
            ?.takeIf { it.isNotBlank() }
            ?: "No notifications"
    }

    fun replyToLast(packageName: String, replyText: String): Boolean {
        val active = activeNotifications ?: return false
        val sbn = if (packageName.isBlank())
            active.lastOrNull()
        else
            active.lastOrNull { it.packageName == packageName }
        sbn ?: return false

        val actions = sbn.notification?.actions ?: return false
        for (action in actions) {
            val remoteInputs = action.remoteInputs ?: continue
            if (remoteInputs.isEmpty()) continue
            try {
                val replyIntent = Intent()
                android.app.RemoteInput.addResultsToIntent(
                    remoteInputs, replyIntent,
                    Bundle().apply { putCharSequence(remoteInputs[0].resultKey, replyText) }
                )
                action.actionIntent.send(this, 0, replyIntent)
                Log.d(TAG, "Reply sent to ${sbn.packageName}")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "replyToLast error: ${e.message}")
            }
        }
        return false
    }
}