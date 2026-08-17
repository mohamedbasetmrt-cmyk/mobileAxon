package com.example.app_abdelbaset

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper

enum class AxonMode { SERVER, LOCAL }

// ── NEW: Local LLM Provider types ──────────────────────────────────
enum class LocalLlmProviderType { GEMMA_4B, COHERE_API, DAHL_API, MISTRAL_API, GROQ_API }

class MicForegroundService : Service() {

    private var wakeLock:         PowerManager.WakeLock? = null
    private var wakeWordDetector: WakeWordDetector?      = null
    private var voiceSession:     AxonVoiceSession?      = null
    private var localVoiceSession: LocalVoiceSession?    = null
    private var currentMode: AxonMode = AxonMode.SERVER
    private var currentLocalProvider: LocalLlmProviderType = LocalLlmProviderType.GEMMA_4B
    private var hasShownReadyToast = false
    private var ttsEngine: TtsEngine? = null

    @Volatile
    private var isShuttingDown = false
    private var pendingWidgetCommand: String? = null

    companion object {
        private const val TAG        = "MicForegroundService"
        private const val NOTIF_ID   = 1001
        private const val CHANNEL_ID = "mic_service_channel"
        const val ACTION_WIDGET_COMMAND = "com.example.app_abdelbaset.WIDGET_COMMAND"
        const val EXTRA_COMMAND_TEXT    = "command_text"
        const val EXTRA_MODE            = "axon_mode"

        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        isRunning = true
        val filter = IntentFilter(ACTION_WIDGET_COMMAND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(widgetCommandReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(widgetCommandReceiver, filter)
        }
        AxonCircleWidget.updateAllWidgets(applicationContext)
        ServiceStatsTracker.onServiceStarted()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Axon::MicWakeLock")
        wakeLock?.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        try {
            createNotificationChannel()
            startForeground(NOTIF_ID, buildNotification("Initialising..."))
            sendBroadcast(Intent("SERVICE_STARTED"))

            try {
                val overlayIntent = Intent(this, HologramOverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    startForegroundService(overlayIntent)
                else
                    startService(overlayIntent)
            } catch (e: Exception) { Log.w(TAG, "Could not start HologramOverlayService: ${e.message}") }

            val prefs = getSharedPreferences("axon_prefs", Context.MODE_PRIVATE)
            val modeStr = intent?.getStringExtra(EXTRA_MODE)
                ?: prefs.getString("axon_mode", "SERVER") ?: "SERVER"
            currentMode = try { AxonMode.valueOf(modeStr) } catch (_: Exception) { AxonMode.SERVER }

            val providerStr = prefs.getString("local_llm_provider", "GEMMA_4B") ?: "GEMMA_4B"
            currentLocalProvider = try {
                LocalLlmProviderType.valueOf(providerStr)
            } catch (_: Exception) { LocalLlmProviderType.GEMMA_4B }

            // ← NEW: شغّل initLocalMode على خيط منفصل لتفادي ANR
            if (currentMode == AxonMode.LOCAL) {
                Thread {
                    try {
                        initLocalMode()
                    } catch (e: Throwable) {
                        Log.e(TAG, "FATAL in initLocalMode", e)
                        ErrorLogger.logError(applicationContext, TAG, "FATAL initLocalMode", e)
                        runOnUiThread {
                            showToast("Local init failed: ${e.message}")
                            updateNotification("Local init failed")
                        }
                    }
                }.apply { name = "LocalInitThread"; isDaemon = true; start() }
            } else {
                val endpoint = prefs.getString("endpoint", MainActivity.PRESET_ENDPOINTS[0])
                    ?: MainActivity.PRESET_ENDPOINTS[0]
                voiceSession = AxonVoiceSession(
                    context             = applicationContext,
                    serverWsBaseUrl   = "wss://$endpoint/mobile",
                    serverHttpBaseUrl = "https://$endpoint/mobile",
                    onStateChanged      = { state -> handleSessionState(state) },
                    onPartialTranscript = { text  -> Log.d(TAG, "Partial: $text") },
                    onFinalTranscript   = { text  -> updateNotification("$text") },
                    onLlmResponse       = { response -> Log.d(TAG, "LLM: ${response.take(60)}") },
                    onProgress          = { msg -> updateNotification(msg) },
                    onError             = { err ->
                        Log.e(TAG, "Session error: $err")
                        showToast("Server down: $endpoint")
                        updateNotification("Error - say Axon to retry")
                    }
                )
                checkServerThenStart(endpoint)
            }

            if (currentMode != AxonMode.LOCAL) {
                ttsEngine = MainActivity.getSharedTtsEngine()
                if (ttsEngine == null) {
                    ttsEngine = LocalTtsEngine(applicationContext)
                    ttsEngine?.init()
                }
            }

            intent?.getStringExtra(EXTRA_COMMAND_TEXT)?.let { pendingWidgetCommand = it }
            return START_STICKY
        } catch (e: Throwable) {
            Log.e(TAG, "FATAL in onStartCommand", e)
            ErrorLogger.logError(applicationContext, TAG, "FATAL", e)
            showToast("FATAL: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
    }

    private fun speakResponse(text: String) {
        ttsEngine?.speak(text, isLast = true) {
            // onDone
        }
    }

    private fun initLocalMode() {
        updateNotification("Initialising local mode...")

        val llmProvider: LlmProvider = when (currentLocalProvider) {
            LocalLlmProviderType.GEMMA_4B -> {
                updateNotification("Loading Gemma 4B model...")
                LocalLiteRTLMProvider(applicationContext).also { provider ->
                    provider.loadModel(
                        backend = "CPU",
                        onSuccess = {
                            updateNotification("Gemma 4B ready")
                        },
                        onError = { err ->
                            updateNotification("Gemma load failed: $err")
                            showToast("Gemma load failed: $err")
                        }
                    )
                }
            }
            LocalLlmProviderType.COHERE_API -> {
                updateNotification("Connecting to Cohere API...")
                CohereLlmProvider(applicationContext).also { provider ->
                    if (!provider.hasApiKey()) {
                        updateNotification("Cohere API key not set - check Settings")
                        showToast("Cohere API key not set")
                    } else {
                        provider.connect {}
                        updateNotification("Cohere API ready (${provider.currentModel})")
                    }
                }
            }
            LocalLlmProviderType.DAHL_API -> {
                updateNotification("Connecting to Dahl API...")
                DahlLlmProvider(applicationContext).also { provider ->
                    if (!provider.hasApiKey()) {
                        updateNotification("Dahl API key not set - check Settings")
                        showToast("Dahl API key not set")
                    } else {
                        provider.connect {}
                        updateNotification("Dahl API ready (${provider.currentModel})")
                    }
                }
            }
            LocalLlmProviderType.MISTRAL_API -> {
                updateNotification("Connecting to Mistral API...")
                MistralLlmProvider(applicationContext).also { provider ->
                    if (!provider.hasApiKey()) {
                        updateNotification("Mistral API key not set - check Settings")
                        showToast("Mistral API key not set")
                    } else {
                        provider.connect {}
                        updateNotification("Mistral API ready (${provider.currentModel})")
                    }
                }
            }
            LocalLlmProviderType.GROQ_API -> {
                updateNotification("Connecting to Groq API...")
                GroqLlmProvider(applicationContext).also { provider ->
                    if (!provider.hasApiKey()) {
                        updateNotification("Groq API key not set - check Settings")
                        showToast("Groq API key not set")
                    } else {
                        provider.connect {}
                        updateNotification("Groq API ready (${provider.currentModel})")
                    }
                }
            }
        }

        val session = LocalVoiceSession(
            context = applicationContext,
            llmProvider = llmProvider,
            onStateChanged = { state -> handleLocalSessionState(state) },
            onPartialTranscript = { text -> Log.d(TAG, "Local partial: $text") },
            onFinalTranscript   = { text -> updateNotification("You: $text") },
            onLlmResponse       = { response -> Log.d(TAG, "Local LLM: ${response.take(60)}") },
            onProgress          = { msg -> updateNotification(msg) },
            onError             = { err ->
                Log.e(TAG, "Local session error: $err")
                showToast("Local error: $err")
                updateNotification("Error - say Axon to retry")
            }
        )
        localVoiceSession = session

        val sttPrefs = getSharedPreferences("axon_prefs", Context.MODE_PRIVATE)
        val sttModeStr = sttPrefs.getString("stt_mode", "LOCAL") ?: "LOCAL"
        val sttMode = try { SttMode.valueOf(sttModeStr) } catch (_: Exception) { SttMode.LOCAL }
        val dgKey = sttPrefs.getString("deepgram_api_key", "") ?: ""

        session.initialize(
            llmBackend = "CPU",
            sttMode = sttMode,
            deepgramApiKey = dgKey,
        ) { initOk ->
            if (initOk) {
                if (currentLocalProvider == LocalLlmProviderType.COHERE_API) {
                    updateNotification("Local mode ready (Cohere)")
                    startWakeWordDetection()
                }
                if (currentLocalProvider == LocalLlmProviderType.DAHL_API) {
                    updateNotification("Local mode ready (Dahl)")
                    startWakeWordDetection()
                }
                if (currentLocalProvider == LocalLlmProviderType.MISTRAL_API) {
                    updateNotification("Local mode ready (Mistral)")
                    startWakeWordDetection()
                }
                if (currentLocalProvider == LocalLlmProviderType.GROQ_API) {
                    updateNotification("Local mode ready (Groq)")
                    startWakeWordDetection()
                }
                // For Gemma, loadLlmModel is already called above
            } else {
                updateNotification("Local init failed - check models in Settings")
                showToast("Local init failed - check Settings")
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy called")

        isShuttingDown = true
        stopForeground(STOP_FOREGROUND_REMOVE)

        wakeWordDetector?.stop()
        wakeWordDetector?.release()
        wakeWordDetector = null

        voiceSession?.release()
        voiceSession = null

        localVoiceSession?.release()
        localVoiceSession = null

        try { stopService(Intent(this, HologramOverlayService::class.java)) }
        catch (e: Exception) { Log.w(TAG, "Could not stop HologramOverlayService: ${e.message}") }

        wakeLock?.release()
        wakeLock = null

        try { unregisterReceiver(widgetCommandReceiver) } catch (_: Exception) {}

        isRunning = false
        AxonCircleWidget.updateAllWidgets(applicationContext)
        ServiceStatsTracker.onServiceStopped()

        sendBroadcast(Intent("SERVICE_STOPPED"))

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun checkServerThenStart(endpoint: String) {
        val backupEndpoint = "2km312-axonmobile.hf.space"
        updateNotification("Checking server connection...")
        Thread {
            fun isReachable(host: String): Boolean {
                return try {
                    val url = java.net.URL("https://$host/mobile/health")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout    = 15000
                    conn.requestMethod  = "GET"
                    val code = conn.responseCode
                    conn.disconnect()
                    code in 200..299
                } catch (e: Exception) {
                    Log.w(TAG, "❌ $host unreachable: ${e.message}")
                    false
                }
            }

            val activeEndpoint = when {
                isReachable(endpoint) -> {
                    Log.d(TAG, "✅ Primary server reachable: $endpoint")
                    endpoint
                }
                isReachable(backupEndpoint) -> {
                    Log.d(TAG, "⚠️ Primary failed, using backup: $backupEndpoint")
                    backupEndpoint
                }
                else -> null
            }

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (activeEndpoint != null) {
                    voiceSession?.release()
                    voiceSession = AxonVoiceSession(
                        context             = applicationContext,
                        serverWsBaseUrl   = "wss://$activeEndpoint/mobile",
                        serverHttpBaseUrl = "https://$activeEndpoint/mobile",
                        useDirectDeepgram   = (activeEndpoint == backupEndpoint),
                        deepgramApiKey      = "d2a0ab9e97530f66bc12d44c715ae11ea781f4ea",
                        onStateChanged      = { state -> handleSessionState(state) },
                        onPartialTranscript = { text  -> Log.d(TAG, "Partial: $text") },
                        onFinalTranscript   = { text  -> updateNotification(text) },
                        onLlmResponse       = { response -> Log.d(TAG, "LLM: ${response.take(60)}") },
                        onProgress          = { msg -> updateNotification(msg) },
                        onError             = { err ->
                            Log.e(TAG, "Session error: $err")
                            showToast("Server down: $endpoint")
                            updateNotification("Error - say Axon to retry")
                        }
                    )
                    updateNotification("Connected to: $activeEndpoint")
                    startWakeWordDetection()
                    pendingWidgetCommand?.let {
                        Log.d(TAG, "Executing pending widget command: $it")
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            voiceSession?.sendDirectCommand(it)
                        }, 1500)
                        pendingWidgetCommand = null
                    }
                } else {
                    Log.e(TAG, "❌ All servers unreachable")
                    updateNotification("No server available - check connection")
                    showToast("❌ All servers unreachable")
                }
            }
        }.apply { name = "ServerCheckThread"; isDaemon = true; start() }
    }

    private fun startWakeWordDetection() {
        if (isShuttingDown) {
            Log.d(TAG, "Skipping wake word start - service is shutting down")
            return
        }
        try {
            val detector = WakeWordDetector(
                context            = applicationContext,
                onWakeWordDetected = { onWakeWordDetected() },
                onError            = { e ->
                    Log.e(TAG, "Wake word error", e)
                    ErrorLogger.logError(applicationContext, TAG, "Wake word error", e)
                    showToast("Wake word error: ${e.message}")
                    updateNotification("Wake word error: ${e.message}")
                },
                onScoreUpdate       = { score ->
                    val pct = (score * 100).toInt()
                    updateNotification("Listening for Axon...")
                }
            )
            wakeWordDetector = detector
            detector.start()
            if (!hasShownReadyToast) {
                showToast("Axon is Ready")
                hasShownReadyToast = true
            }
            if (detector.isRunning()) {
                updateNotification("Listening for Axon...")
            } else {
                showToast("Wake word init failed - check Settings > Diagnostics")
                updateNotification("Wake word init failed - check Settings > Diagnostics")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start WakeWordDetector", e)
            ErrorLogger.logError(applicationContext, TAG, "Failed to start WakeWordDetector", e)
            showToast("Wake word crash: ${e.message}")
            updateNotification("Wake word crash: ${e.message}")
        }
    }

    private fun onWakeWordDetected() {
        Log.d(TAG, "Wake word detected!")
        VisualizerState.setState(VisualizerState.OrbState.LISTENING)
        showToast("Wake word detected!")
        updateNotification("Axon activated - listening...")

        if (currentMode == AxonMode.LOCAL) {
            localVoiceSession?.onWakeWordDetected()
        } else {
            voiceSession?.onWakeWordDetected()
        }

        try {
            wakeWordDetector?.stop()
            wakeWordDetector?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing wake word detector: ${e.message}")
        }
        wakeWordDetector = null
    }

    private fun handleSessionState(state: AxonVoiceSession.State) {
        Log.d(TAG, "Session state: $state")
        when (state) {
            AxonVoiceSession.State.IDLE -> {
                ServiceStatsTracker.onQueryFinished()
                updateNotification("Listening for Axon...")

                if (isShuttingDown) {
                    Log.d(TAG, "Service shutting down, NOT restarting wake word")
                    return
                }

                if (wakeWordDetector == null) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!isShuttingDown) startWakeWordDetection()
                    }, 500)
                }
            }
            AxonVoiceSession.State.STREAMING_STT -> {
                ServiceStatsTracker.onQueryStarted()
                updateNotification("Listening...")
            }
            AxonVoiceSession.State.WAITING_TRANSCRIPT -> updateNotification("Processing speech...")
            AxonVoiceSession.State.LLM_THINKING       -> updateNotification("Thinking...")
            AxonVoiceSession.State.TTS_PLAYING        -> updateNotification("Speaking...")
            AxonVoiceSession.State.ERROR -> {
                showToast("Session error - say Axon to retry")
                updateNotification("Error - say Axon to retry")
                if (wakeWordDetector == null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        startWakeWordDetection()
                    }, 500)
                } else {
                    try { wakeWordDetector?.start() }
                    catch (e: Throwable) { Log.e(TAG, "Failed to restart WakeWordDetector", e) }
                }
            }
        }
    }

    private fun handleLocalSessionState(state: LocalVoiceSession.State) {
        Log.d(TAG, "Local session state: $state")
        when (state) {
            LocalVoiceSession.State.IDLE -> {
                ServiceStatsTracker.onQueryFinished()
                updateNotification("Listening for Axon...")
                VisualizerState.setState(VisualizerState.OrbState.IDLE)
                if (wakeWordDetector == null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        startWakeWordDetection()
                    }, 500)
                } else {
                    try { wakeWordDetector?.start() }
                    catch (e: Throwable) { Log.e(TAG, "Failed to restart WakeWordDetector", e) }
                }
            }
            LocalVoiceSession.State.STREAMING_STT -> {
                ServiceStatsTracker.onQueryStarted()
                updateNotification("Listening...")
            }
            LocalVoiceSession.State.LLM_THINKING -> updateNotification("Thinking...")
            LocalVoiceSession.State.TTS_PLAYING  -> updateNotification("Speaking...")
            LocalVoiceSession.State.ERROR -> {
                showToast("Local session error")
                updateNotification("Error - say Axon to retry")
                if (wakeWordDetector == null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        startWakeWordDetection()
                    }, 500)
                } else {
                    try { wakeWordDetector?.start() }
                    catch (e: Throwable) { Log.e(TAG, "Failed to restart WakeWordDetector", e) }
                }
            }
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(statusText: String): Notification {
        val stopPending = PendingIntent.getBroadcast(
            this, 0, Intent(this, StopReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val mainPending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Axon Voice Assistant")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(mainPending)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPending)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun showToast(msg: String) {
        runOnUiThread { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }

    private fun runOnUiThread(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    private val widgetCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_WIDGET_COMMAND) return
            val text = intent.getStringExtra(EXTRA_COMMAND_TEXT) ?: return
            Log.d(TAG, "Widget command received: $text")
            if (currentMode == AxonMode.LOCAL) {
                showToast("Widget commands not available in local mode")
            } else {
                voiceSession?.sendDirectCommand(text)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Voice Assistant Service", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Listens for Axon wake word and processes voice commands"
                setShowBadge(false)
            }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
        }
    }
}