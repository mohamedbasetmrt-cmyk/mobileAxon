package com.example.app_abdelbaset

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Chat
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.GridView
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import android.graphics.Paint
import com.example.app_abdelbaset.ui.theme.App_abdelbasetTheme
import com.example.app_abdelbaset.ui.theme.BgPrimary
import com.example.app_abdelbaset.ui.theme.BgSecondary
import com.example.app_abdelbaset.ui.theme.CardBg
import com.example.app_abdelbaset.ui.theme.CardBorder
import com.example.app_abdelbaset.ui.theme.CardBorder2
import com.example.app_abdelbaset.ui.theme.TextPrimary
import com.example.app_abdelbaset.ui.theme.TextMuted
import com.example.app_abdelbaset.ui.theme.NeonGreen
import com.example.app_abdelbaset.ui.theme.NeonCyan
import com.example.app_abdelbaset.ui.theme.AccentPink
import com.example.app_abdelbaset.ui.theme.AccentAmber
import com.example.app_abdelbaset.ChatSession

private enum class Screen { MAIN, SETTINGS, CHAT, WIDGET, PAIR_DESKTOP ,NOTIFICATION_RULES}

// ── Fake stats (replace with real data from your service) ────────────
//private var queryCount   by mutableStateOf(14)
//private var avgResponse  by mutableStateOf("2.1s")
//private var isOnline     by mutableStateOf(true)

class MainActivity : ComponentActivity() {

    private var isServiceRunning   by mutableStateOf(false)
    private var permissionsGranted by mutableStateOf(false)
    private var overlayPermGranted by mutableStateOf(false)
    private var diagnosticResult   by mutableStateOf<DiagnosticResult?>(null)
    private var errorLogPath       by mutableStateOf("")
    private var pendingCallNumber: String = ""

    private val prefs by lazy { getSharedPreferences("axon_prefs", Context.MODE_PRIVATE) }
    private val pairingManager by lazy { PairingManager(applicationContext) }

    private var selectedEndpoint by mutableStateOf("")
    private var customEndpoint   by mutableStateOf("")
    private var selectedTtsEngine by mutableStateOf(TtsEngineType.ANDROID_TTS)
    private var sherpaTtsEngine: SherpaTtsEngine? = null
    private var supertonicSid by mutableStateOf(6)
    private var supertonicSpeed by mutableStateOf(1.25f)
    private var supertonicNumSteps by mutableStateOf(8)
    private var vitsSid by mutableStateOf(0)
    private var vitsSpeed by mutableStateOf(1.0f)
    private var vitsSilenceScale by mutableStateOf(0.2f)
    private var customModelDir by mutableStateOf("")
    private var selectedAndroidTtsEnginePkg by mutableStateOf("")

    private var currentMode by mutableStateOf(AxonMode.SERVER)
    private var currentLocalProvider by mutableStateOf(LocalLlmProviderType.GEMMA_4B)
    private var cohereApiKey by mutableStateOf("")
    private var cohereModel by mutableStateOf("command-a-plus-05-2026")
    private var cohereProvider: CohereLlmProvider? = null
    private var dahlApiKey by mutableStateOf("")
    private var dahlModel by mutableStateOf("MiniMaxAI/MiniMax-M2.7")
    private var dahlProvider: DahlLlmProvider? = null
    private var currentSttMode by mutableStateOf(SttMode.LOCAL)
    private var deepgramApiKey by mutableStateOf("")

    data class ModelsStatus(
        val stt: Boolean = false,
        val vad: Boolean = false,
        val llm: Boolean = false,
        val tts: Boolean = false,
        val ttsEngineType: TtsEngineType = TtsEngineType.ANDROID_TTS,
        val sherpaModelsReady: Boolean = false,
    )
    private var localModelsStatus by mutableStateOf(ModelsStatus())
    private var ttsEnginesList by mutableStateOf<List<TtsEngineLister.TtsEngineInfo>>(emptyList())

    companion object {
        private const val TAG = "MainActivity"
        private const val PREF_ENDPOINT        = "endpoint"
        private const val PREF_CUSTOM_ENDPOINT = "custom_endpoint"
        private const val PREF_AXON_MODE       = "axon_mode"
        private const val PREF_TTS_ENGINE = "tts_engine"
        private const val PREF_TTS_MODEL_DIR = "tts_model_dir"
        private const val PREF_ANDROID_TTS_PKG = "android_tts_pkg"
        private const val PREF_STT_MODE = "stt_mode"
        private const val PREF_DEEPGRAM_API_KEY = "deepgram_api_key"
        private const val PREF_DEEPGRAM_TTS_KEY = "deepgram_tts_api_key"
        private const val PREF_DEEPGRAM_TTS_VOICE = "deepgram_tts_voice"
        private const val PREF_SERVER_CONNECT_ENABLED = "server_connect_enabled"
        private const val REQUEST_CALL_PERMISSION = 1001
        @JvmStatic
        private var sharedTtsEngine: TtsEngine? = null

        @JvmStatic
        fun setSharedTtsEngine(engine: TtsEngine?) {
            sharedTtsEngine = engine
        }

        @JvmStatic
        fun getSharedTtsEngine(): TtsEngine? = sharedTtsEngine


        val PRESET_ENDPOINTS = listOf(
            "aivision.tail7c1d28.ts.net",
            "inclinational-perthitic-jenna.ngrok-free.dev"
        )
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        permissionsGranted = allGranted
        if (allGranted) {
            Toast.makeText(this, "✅ All permissions granted!", Toast.LENGTH_SHORT).show()
            runDiagnostics()
        } else {
            Toast.makeText(this, "❌ Please grant all permissions!", Toast.LENGTH_LONG).show()
        }
    }

    private val overlayPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        overlayPermGranted = checkOverlayPermission()
        if (overlayPermGranted)
            Toast.makeText(this, "✅ Overlay permission granted!", Toast.LENGTH_SHORT).show()
        else
            Toast.makeText(this, "⚠️ Overlay denied", Toast.LENGTH_LONG).show()
    }

    private val serviceStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "SERVICE_STARTED" -> isServiceRunning = true
                "SERVICE_STOPPED" -> isServiceRunning = false
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // ═══════════════════════════════════════════════════════════════
        // INIT MANAGERS - MUST BE CALLED BEFORE ANYTHING ELSE
        // ═══════════════════════════════════════════════════════════════
        SystemPromptManager.init(applicationContext)
        ChatSummaryManager.init(applicationContext)  // ← NEW: Initialize Chat Summary Manager
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        selectedEndpoint = prefs.getString(PREF_ENDPOINT, PRESET_ENDPOINTS[0]) ?: PRESET_ENDPOINTS[0]
        customEndpoint   = prefs.getString(PREF_CUSTOM_ENDPOINT, "") ?: ""
        currentMode      = try {
            AxonMode.valueOf(prefs.getString(PREF_AXON_MODE, "SERVER") ?: "SERVER")
        } catch (_: Exception) { AxonMode.SERVER }

        currentLocalProvider = try {
            LocalLlmProviderType.valueOf(prefs.getString("local_llm_provider", "GEMMA_4B") ?: "GEMMA_4B")
        } catch (_: Exception) { LocalLlmProviderType.GEMMA_4B }

        cohereApiKey = prefs.getString("cohere_api_key", "") ?: ""
        cohereModel = prefs.getString("cohere_model", "command-a-plus-05-2026") ?: "command-a-plus-05-2026"

        dahlApiKey = prefs.getString("dahl_api_key", "") ?: ""
        dahlModel = prefs.getString("dahl_model", "MiniMaxAI/MiniMax-M2.7") ?: "MiniMaxAI/MiniMax-M2.7"

        currentSttMode = try {
            SttMode.valueOf(prefs.getString(PREF_STT_MODE, "LOCAL") ?: "LOCAL")
        } catch (_: Exception) { SttMode.LOCAL }
        deepgramApiKey = prefs.getString(PREF_DEEPGRAM_API_KEY, "") ?: ""

        selectedTtsEngine = try {
            TtsEngineType.valueOf(prefs.getString(PREF_TTS_ENGINE, "ANDROID_TTS") ?: "ANDROID_TTS")
        } catch (_: Exception) { TtsEngineType.ANDROID_TTS }

        supertonicSid = prefs.getInt("supertonic_sid", 6)
        supertonicSpeed = prefs.getFloat("supertonic_speed", 1.25f)
        supertonicNumSteps = prefs.getInt("supertonic_steps", 8)
        vitsSid = prefs.getInt("vits_sid", 0)
        vitsSpeed = prefs.getFloat("vits_speed", 1.0f)
        vitsSilenceScale = prefs.getFloat("vits_silence", 0.2f)
        customModelDir = prefs.getString(PREF_TTS_MODEL_DIR, "") ?: ""
        selectedAndroidTtsEnginePkg = prefs.getString(PREF_ANDROID_TTS_PKG, "") ?: ""

        errorLogPath = ErrorLogger.getLogFilePath(applicationContext)


        val filter = IntentFilter().apply {
            addAction("SERVICE_STARTED")
            addAction("SERVICE_STOPPED")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(serviceStatusReceiver, filter, RECEIVER_NOT_EXPORTED)
        else
            registerReceiver(serviceStatusReceiver, filter)

        // Register call permission receiver
        val callFilter = IntentFilter("com.example.app_abdelbaset.REQUEST_CALL_PERMISSION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(callPermissionReceiver, callFilter, RECEIVER_NOT_EXPORTED)
        else
            registerReceiver(callPermissionReceiver, callFilter)

        permissionsGranted = checkAllPermissions()
        overlayPermGranted = checkOverlayPermission()

        setContent {
            var currentScreen    by remember { mutableStateOf(Screen.MAIN) }
            var localCustomEp    by remember { mutableStateOf(customEndpoint) }
            var localSelected    by remember { mutableStateOf(selectedEndpoint) }
            var errorLogContent  by remember { mutableStateOf("") }
            var showErrorLog     by remember { mutableStateOf(false) }
            var showDiagnostics  by remember { mutableStateOf(false) }
            var showDevDashboard by remember { mutableStateOf(false) }

            BackHandler(enabled = showErrorLog) {
                showErrorLog = false
            }
            BackHandler(enabled = currentScreen == Screen.SETTINGS) {
                currentScreen = Screen.MAIN
            }
            BackHandler(enabled = currentScreen == Screen.WIDGET) {
                currentScreen = Screen.MAIN
            }
            BackHandler(enabled = currentScreen == Screen.CHAT) {
                currentScreen = Screen.MAIN
            }
            BackHandler(enabled = currentScreen == Screen.NOTIFICATION_RULES) {
                currentScreen = Screen.SETTINGS
            }


            App_abdelbasetTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                ) {
                    when {
                        showDevDashboard -> DevDashboardScreen(
                            onClose = { showDevDashboard = false }
                        )
                        
                        showErrorLog -> ErrorLogScreen(
                            logContent = errorLogContent,
                            logPath = errorLogPath,
                            onClose = { showErrorLog = false },
                            onClear = {
                                if (ErrorLogger.clearLog(applicationContext)) {
                                    errorLogContent = ""
                                    showErrorLog = false
                                    Toast.makeText(applicationContext, "✅ Log cleared", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onOpenDevDashboard = { showDevDashboard = true }
                        )

                        currentScreen == Screen.CHAT -> ChatScreen(
                            onBack = { currentScreen = Screen.MAIN },
                            onOpenSettings = { currentScreen = Screen.SETTINGS },
                            onOpenWidget = { currentScreen = Screen.WIDGET },
                            onOpenPair = { currentScreen = Screen.PAIR_DESKTOP }
                        )
                        currentScreen == Screen.WIDGET -> WidgetManagerScreen(
                            onBack         = { currentScreen = Screen.MAIN },
                            onOpenSettings = { currentScreen = Screen.SETTINGS },
                            onOpenChat     = { currentScreen = Screen.CHAT },
                            onOpenPair     = { currentScreen = Screen.PAIR_DESKTOP }
                        )

                        currentScreen == Screen.PAIR_DESKTOP -> PairDesktopScreen(
                            onBack = { currentScreen = Screen.MAIN },
                            onOpenSettings = { currentScreen = Screen.SETTINGS },
                            onOpenChat = { currentScreen = Screen.CHAT },
                            onOpenWidget = { currentScreen = Screen.WIDGET },
                            endpoint = selectedEndpoint,
                            pairingManager = pairingManager,
                            onPaired = {
                                currentScreen = Screen.MAIN
                                refreshPairedDevices()
                                initPairingService()
                            },
                            onUnpaired = { stopPairingService() }
                        )

                        currentScreen == Screen.NOTIFICATION_RULES -> NotificationRulesScreen(
                            onBack = { currentScreen = Screen.SETTINGS }
                        )

                        currentScreen == Screen.SETTINGS -> SettingsScreen(
                            isServiceRunning = isServiceRunning,
                            presetEndpoints = PRESET_ENDPOINTS,
                            selectedEndpoint = localSelected,
                            customEndpoint = localCustomEp,
                            diagnosticResult = diagnosticResult,
                            showDiagnostics = showDiagnostics,
                            overlayPermGranted = overlayPermGranted,
                            currentMode = currentMode,
                            modelsStatus = localModelsStatus,
                            ttsEnginesList = ttsEnginesList,
                            currentTtsEngine = selectedTtsEngine,
                            selectedAndroidTtsEnginePkg = selectedAndroidTtsEnginePkg,
                            onAndroidTtsEngineSelected = { onAndroidTtsEngineSelected(it) },
                            supertonicSid = supertonicSid,
                            supertonicSpeed = supertonicSpeed,
                            supertonicNumSteps = supertonicNumSteps,
                            vitsSid = vitsSid,
                            vitsSpeed = vitsSpeed,
                            vitsSilenceScale = vitsSilenceScale,
                            customModelDir = customModelDir,
                            onTtsEngineChange = { onTtsEngineChange(it) },
                            onSupertonicConfigChange = { sid, speed, steps -> onSupertonicConfigChange(sid, speed, steps) },
                            onVitsConfigChange = { sid, speed, silence -> onVitsConfigChange(sid, speed, silence) },
                            onCustomModelDirChange = { onCustomModelDirChange(it) },
                            onModeChange = { newMode ->
                                currentMode = newMode
                                prefs.edit().putString(PREF_AXON_MODE, newMode.name).apply()
                                if (isServiceRunning) {
                                    stopListeningService()
                                    android.os.Handler(android.os.Looper.getMainLooper())
                                        .postDelayed({ startListeningService() }, 800)
                                }
                            },
                            currentLocalProvider = currentLocalProvider,
                            cohereApiKey = cohereApiKey,
                            cohereModel = cohereModel,
                            dahlApiKey = dahlApiKey,
                            dahlModel = dahlModel,
                            onLocalProviderChange = { onLocalProviderChange(it) },
                            onCohereApiKeyChange = { onCohereApiKeyChange(it) },
                            onCohereModelChange = { onCohereModelChange(it) },
                            onDahlApiKeyChange = { onDahlApiKeyChange(it) },
                            onDahlModelChange = { onDahlModelChange(it) },
                            onInitLocalModels = { checkLocalModels() },
                            onSelectedEndpointChange = { ep ->
                                localSelected = ep
                                selectedEndpoint = ep
                                prefs.edit().putString(PREF_ENDPOINT, ep).apply()
                                if (isServiceRunning) {
                                    stopListeningService()
                                    android.os.Handler(android.os.Looper.getMainLooper())
                                        .postDelayed({ startListeningService() }, 800)
                                }
                            },
                            onCustomEndpointChange = { ep ->
                                localCustomEp = ep
                                customEndpoint = ep
                                prefs.edit().putString(PREF_CUSTOM_ENDPOINT, ep).apply()
                                if (isServiceRunning) {
                                    stopListeningService()
                                    android.os.Handler(android.os.Looper.getMainLooper())
                                        .postDelayed({ startListeningService() }, 800)
                                }
                            },
                            onRequestOverlay = { requestOverlayPermission() },
                            onRunDiagnostics = { runDiagnostics(); showDiagnostics = true },
                            onToggleDiagnostics = { showDiagnostics = !showDiagnostics },
                            onViewErrorLog = {
                                errorLogContent = ErrorLogger.readLog(applicationContext); showErrorLog = true
                            },
                            permissionsGranted = permissionsGranted,
                            onOpenChat     = { currentScreen = Screen.CHAT },
                            onOpenWidget   = { currentScreen = Screen.WIDGET },
                            onOpenPair     = { currentScreen = Screen.PAIR_DESKTOP },
                            onBack = { currentScreen = Screen.MAIN },
                            currentSttMode = currentSttMode,
                            deepgramApiKey = deepgramApiKey,
                            onSttModeChange = { onSttModeChange(it) },
                            onDeepgramApiKeyChange = { onDeepgramApiKeyChange(it) },
                            onOpenNotificationRules = { currentScreen = Screen.NOTIFICATION_RULES },

                        )

                        else -> MainScreen(
                            isServiceRunning = isServiceRunning,
                            permissionsGranted = permissionsGranted,
                            onStartClick = { startListeningService() },
                            onStopClick = { stopListeningService() },
                            onRequestPerms = { requestAllPermissions() },
                            onOpenSettings = { currentScreen = Screen.SETTINGS },
                            onOpenChat = { currentScreen = Screen.CHAT },
                            onOpenWidget = { currentScreen = Screen.WIDGET },
                            onOpenPair = { currentScreen = Screen.PAIR_DESKTOP }
                        )
                    }
                }
            }
        }

        if (!permissionsGranted) requestAllPermissions() else runDiagnostics()
    }
    // ── Call Permission Receiver ─────────────────────────────────────
    private val callPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.app_abdelbaset.REQUEST_CALL_PERMISSION") {
                pendingCallNumber = intent.getStringExtra("number") ?: ""
                if (pendingCallNumber.isNotBlank()) {
                    androidx.core.app.ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(Manifest.permission.CALL_PHONE),
                        REQUEST_CALL_PERMISSION
                    )
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            REQUEST_CALL_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (pendingCallNumber.isNotBlank()) {
                        val intent = Intent(Intent.ACTION_CALL,
                            Uri.parse("tel:$pendingCallNumber")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                        pendingCallNumber = ""
                    }
                } else {
                    Toast.makeText(this, "Call permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isServiceRunning   = MicForegroundService.isRunning
        overlayPermGranted = checkOverlayPermission()
        if (pairingManager.isPaired()) initPairingService()
        Log.d(TAG, "onResume: isRunning=${MicForegroundService.isRunning}")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(serviceStatusReceiver)
        unregisterReceiver(callPermissionReceiver)
    }
    private fun onAndroidTtsEngineSelected(pkg: String) {
        selectedAndroidTtsEnginePkg = pkg
        prefs.edit().putString(PREF_ANDROID_TTS_PKG, pkg).apply()

        if (selectedTtsEngine == TtsEngineType.ANDROID_TTS) {
            // ← FIX: أقفل الـ engine القديم وخلي الـ shared null
            getSharedTtsEngine()?.release()
            setSharedTtsEngine(null)

            // ← FIX: استنى 800ms قبل ما تعمل init جديد عشان الـ shutdown القديم يخلص
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    val newEngine = LocalTtsEngine(applicationContext, pkg.ifEmpty { null })
                    newEngine.init()
                    setSharedTtsEngine(newEngine)
                    Toast.makeText(applicationContext, "TTS Engine Updated", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to init TTS engine", e)
                    Toast.makeText(applicationContext, "TTS init failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }, 800)
        }
    }

    private fun checkOverlayPermission() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Toast.makeText(this, "Enable 'Display over other apps'", Toast.LENGTH_LONG).show()
            overlayPermLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun requestAllPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissionLauncher.launch(perms.toTypedArray())
    }

    private fun checkAllPermissions(): Boolean {
        val audio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else true
        return audio && notif
    }

    private fun refreshPairedDevices() { }

    private fun initPairingService() {
        val activeEndpoint = if (selectedEndpoint == "Custom\u2026") customEndpoint else selectedEndpoint
        val intent = Intent(this, PairingForegroundService::class.java)
        intent.putExtra(PairingForegroundService.EXTRA_ENDPOINT, activeEndpoint)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(intent)
        else
            startService(intent)
    }

    private fun stopPairingService() {
        stopService(Intent(this, PairingForegroundService::class.java))
    }

    private fun startListeningService() {
        if (!checkAllPermissions()) { requestAllPermissions(); return }
        
        // Check if server is enabled when in SERVER mode
        val serverConnectEnabled = prefs.getBoolean(PREF_SERVER_CONNECT_ENABLED, false)
        if (currentMode == AxonMode.SERVER && !serverConnectEnabled) {
            Toast.makeText(this, "⚠️ Turn on server in Settings › Backend first", Toast.LENGTH_LONG).show()
            return
        }
        
        if (diagnosticResult?.allPassed != true && currentMode == AxonMode.SERVER) {
            Toast.makeText(this, "⚠️ Fix issues in Settings first!", Toast.LENGTH_LONG).show(); return
        }
        try {
            val activeEndpoint = if (selectedEndpoint == "Custom…") customEndpoint else selectedEndpoint
            ErrorLogger.logInfo(applicationContext, TAG, "Connecting to: $activeEndpoint")
            val intent = Intent(this, MicForegroundService::class.java)
            intent.putExtra(MicForegroundService.EXTRA_MODE, currentMode.name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            isServiceRunning = true
        } catch (e: Exception) {
            ErrorLogger.logError(applicationContext, TAG, "Error starting service", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    private fun onLocalProviderChange(newProvider: LocalLlmProviderType) {
        currentLocalProvider = newProvider
        prefs.edit().putString("local_llm_provider", newProvider.name).apply()
        if (isServiceRunning && currentMode == AxonMode.LOCAL) {
            stopListeningService()
            android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed({ startListeningService() }, 800)
        }
    }

    private fun onCohereApiKeyChange(key: String) {
        cohereApiKey = key
        prefs.edit().putString("cohere_api_key", key).apply()
        cohereProvider?.setApiKey(key)
        Toast.makeText(this, "✅ Cohere API key saved", Toast.LENGTH_SHORT).show()
    }

    private fun onCohereModelChange(model: String) {
        cohereModel = model
        prefs.edit().putString("cohere_model", model).apply()
        cohereProvider?.setModel(model)
    }

    private fun onDahlApiKeyChange(key: String) {
        dahlApiKey = key
        prefs.edit().putString("dahl_api_key", key).apply()
        dahlProvider?.setApiKey(key)
        Toast.makeText(this, "✅ Dahl API key saved", Toast.LENGTH_SHORT).show()
    }

    private fun onDahlModelChange(model: String) {
        dahlModel = model
        prefs.edit().putString("dahl_model", model).apply()
        dahlProvider?.setModel(model)
    }

    private fun onSttModeChange(mode: SttMode) {
        currentSttMode = mode
        prefs.edit().putString(PREF_STT_MODE, mode.name).apply()
        if (isServiceRunning && currentMode == AxonMode.LOCAL) {
            stopListeningService()
            android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed({ startListeningService() }, 800)
        }
    }

    private fun onDeepgramApiKeyChange(key: String) {
        deepgramApiKey = key
        prefs.edit().putString(PREF_DEEPGRAM_API_KEY, key).apply()
        Toast.makeText(this, "Deepgram API key saved", Toast.LENGTH_SHORT).show()
    }

    private fun checkLocalModels() {
        val ctx = applicationContext
        val sttOk = try { ctx.assets.list("Stt")?.any { it == "decoder.int8.onnx" } == true } catch (_: Exception) { false }
        val vadOk = try { ctx.assets.list("Vad")?.any { it == "silero_vad.onnx" } == true } catch (_: Exception) { false }
        val llmOk = java.io.File(LocalLiteRTLMProvider.MODEL_PATH).exists()

        val ttsLister = TtsEngineLister(ctx)
        ttsLister.listAllEngines { engines ->
            ttsEnginesList = engines
            val androidTtsOk = engines.isNotEmpty()

            // Check Sherpa models
            // Check Supertonic models
            val supertonicDir = ctx.getExternalFilesDir(null)?.let { java.io.File(it, SherpaTtsEngine.DEFAULT_SUPERTONIC_DIR) }
            val supertonicReady = supertonicDir?.exists() == true ||
                    try { ctx.assets.list(SherpaTtsEngine.DEFAULT_SUPERTONIC_DIR)?.isNotEmpty() == true } catch (_: Exception) { false }

            // Check VITS models
            val vitsDir = ctx.getExternalFilesDir(null)?.let { java.io.File(it, SherpaTtsEngine.DEFAULT_VITS_DIR) }
            val vitsReady = vitsDir?.exists() == true ||
                    try { ctx.assets.list(SherpaTtsEngine.DEFAULT_VITS_DIR)?.isNotEmpty() == true } catch (_: Exception) { false }

            localModelsStatus = ModelsStatus(
                stt = sttOk, vad = vadOk, llm = llmOk, tts = androidTtsOk,
                ttsEngineType = selectedTtsEngine,
                sherpaModelsReady = supertonicReady || vitsReady
            )

            val allOk = when(selectedTtsEngine) {
                TtsEngineType.ANDROID_TTS -> sttOk && vadOk && llmOk && androidTtsOk
                TtsEngineType.SHERPA_SUPERTONIC -> sttOk && vadOk && llmOk && supertonicReady
                TtsEngineType.SHERPA_VITS_PIPER -> sttOk && vadOk && llmOk && vitsReady
                TtsEngineType.DEEPGRAM_TTS -> {
                    val deepgramKey = prefs.getString(PREF_DEEPGRAM_TTS_KEY, "") ?: ""
                    sttOk && vadOk && llmOk && deepgramKey.isNotBlank()
                }
            }

            Toast.makeText(ctx, if (allOk) "✅ All local models ready" else "⚠️ Some models missing", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopListeningService() {
        try {
            stopService(Intent(this, MicForegroundService::class.java))
            isServiceRunning = false
            // امنع أي finish call
            Log.d(TAG, "Service stopped, UI still active")
        } catch (e: Exception) {
            Toast.makeText(this, "Error stopping service", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runDiagnostics() {
        diagnosticResult = DiagnosticHelper.runAllChecks(applicationContext)
        DiagnosticHelper.printSummary(diagnosticResult!!)
    }
    private fun onTtsEngineChange(newEngine: TtsEngineType) {
        selectedTtsEngine = newEngine
        prefs.edit().putString(PREF_TTS_ENGINE, newEngine.name).apply()
        sherpaTtsEngine?.release()
        sherpaTtsEngine = null

        if (newEngine == TtsEngineType.SHERPA_SUPERTONIC) {
            sherpaTtsEngine = SherpaTtsEngine(applicationContext, TtsEngineType.SHERPA_SUPERTONIC).apply {
                modelDir = customModelDir.ifEmpty { SherpaTtsEngine.DEFAULT_SUPERTONIC_DIR }
                updateGenerationConfig(sid = supertonicSid, speed = supertonicSpeed, numSteps = supertonicNumSteps)
                init()
            }
        } else if (newEngine == TtsEngineType.SHERPA_VITS_PIPER) {
            sherpaTtsEngine = SherpaTtsEngine(applicationContext, TtsEngineType.SHERPA_VITS_PIPER).apply {
                modelDir = customModelDir.ifEmpty { SherpaTtsEngine.DEFAULT_VITS_DIR }
                updateGenerationConfig(sid = vitsSid, speed = vitsSpeed, silenceScale = vitsSilenceScale)
                init()
            }
        }
        val engine = getActiveTtsEngine()
        setSharedTtsEngine(engine)
        Toast.makeText(applicationContext, "TTS: ${newEngine.name}", Toast.LENGTH_SHORT).show()
    }

    private fun onSupertonicConfigChange(sid: Int, speed: Float, numSteps: Int) {
        supertonicSid = sid; supertonicSpeed = speed; supertonicNumSteps = numSteps
        sherpaTtsEngine?.updateGenerationConfig(sid = sid, speed = speed, numSteps = numSteps)
        prefs.edit()
            .putInt("supertonic_sid", sid)
            .putFloat("supertonic_speed", speed)
            .putInt("supertonic_steps", numSteps)
            .apply()
    }

    private fun onVitsConfigChange(sid: Int, speed: Float, silenceScale: Float) {
        vitsSid = sid; vitsSpeed = speed; vitsSilenceScale = silenceScale
        sherpaTtsEngine?.updateGenerationConfig(sid = sid, speed = speed, silenceScale = silenceScale)
        prefs.edit()
            .putInt("vits_sid", sid)
            .putFloat("vits_speed", speed)
            .putFloat("vits_silence", silenceScale)
            .apply()
    }

    private fun onCustomModelDirChange(dir: String) {
        customModelDir = dir
        prefs.edit().putString(PREF_TTS_MODEL_DIR, dir).apply()
        if (selectedTtsEngine != TtsEngineType.ANDROID_TTS) {
            onTtsEngineChange(selectedTtsEngine)
        }
    }

    // دالة عشان تستخدمها في MicForegroundService
    // In MainActivity.kt, line 507 area - change this:
    fun getActiveTtsEngine(): TtsEngine? {
        return when(selectedTtsEngine) {
            TtsEngineType.ANDROID_TTS -> {
                // ← بعتنا الـ package هنا
                LocalTtsEngine(applicationContext, selectedAndroidTtsEnginePkg.ifEmpty { null })
            }
            TtsEngineType.SHERPA_SUPERTONIC, TtsEngineType.SHERPA_VITS_PIPER -> {
                if (sherpaTtsEngine == null || !sherpaTtsEngine!!.isReady) {
                    sherpaTtsEngine = SherpaTtsEngine(applicationContext, selectedTtsEngine).apply {
                        modelDir = customModelDir.ifEmpty {
                            when(selectedTtsEngine) {
                                TtsEngineType.SHERPA_SUPERTONIC -> SherpaTtsEngine.DEFAULT_SUPERTONIC_DIR
                                TtsEngineType.SHERPA_VITS_PIPER -> SherpaTtsEngine.DEFAULT_VITS_DIR
                                else -> SherpaTtsEngine.DEFAULT_SUPERTONIC_DIR
                            }
                        }
                        init()
                    }
                }
                sherpaTtsEngine
            }
            TtsEngineType.DEEPGRAM_TTS -> {
                val apiKey = prefs.getString(PREF_DEEPGRAM_TTS_KEY, "") ?: ""
                val voice = prefs.getString(PREF_DEEPGRAM_TTS_VOICE, "aura-2-en-daniel") ?: "aura-2-en-daniel"
                DeepgramTtsEngine(applicationContext, apiKey, voice, "aura-2")
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
//  MAIN SCREEN
// ══════════════════════════════════════════════════════════════════════

@Composable
fun MainScreen(
    isServiceRunning:   Boolean,
    permissionsGranted: Boolean,
    onStartClick:       () -> Unit,
    onStopClick:        () -> Unit,
    onRequestPerms:     () -> Unit,
    onOpenSettings:     () -> Unit,
    onOpenChat:         () -> Unit,
    onOpenWidget:       () -> Unit,
    onOpenPair:         () -> Unit = {}
) {
    val infiniteTransition = rememberInfiniteTransition(label = "hud")

    // Pulse ring animation
    val pulseScale by infiniteTransition.animateFloat(
        initialValue  = 0.88f,
        targetValue   = 1.12f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1600, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ), label = "pulse"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue  = 0.25f,
        targetValue   = 0.6f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1600, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ), label = "pulseA"
    )

    // Orbit rotation
    val orbitAngle by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing)
        ), label = "orbit"
    )
    val orbitAngle2 by infiniteTransition.animateFloat(
        initialValue  = 360f,
        targetValue   = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing)
        ), label = "orbit2"
    )
    // Bar wave
    val barHeights = (0..4).map { i ->
        infiniteTransition.animateFloat(
            initialValue  = 0.2f,
            targetValue   = 1f,
            animationSpec = infiniteRepeatable(
                animation  = tween(500 + i * 90, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse
            ), label = "bar$i"
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgPrimary)
    ) {
        Column(
            modifier            = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Header ────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // HUD bracket left
                        Text(
                            text     = "[",
                            fontSize = 22.sp,
                            color    = NeonGreen.copy(alpha = 0.6f),
                            fontFamily = AppFontFamily
                        )
                        Text(
                            text          = " AXON ",
                            fontSize      = 24.sp,
                            fontWeight    = FontWeight.Bold,
                            color         = TextPrimary,
                            letterSpacing = 8.sp,
                            fontFamily    = AppFontFamily
                        )
                        Text(
                            text     = "]",
                            fontSize = 22.sp,
                            color    = NeonGreen.copy(alpha = 0.6f),
                            fontFamily = AppFontFamily
                        )
                    }
                    Text(
                        text          = "NEURAL INTERFACE v2.1",
                        fontSize      = 9.sp,
                        color         = TextMuted,
                        letterSpacing = 3.sp,
                        fontFamily    = AppFontFamily
                    )
                }
                // Status badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (isServiceRunning) NeonGreen.copy(0.12f)
                            else CardBg
                        )
                        .border(
                            0.5.dp,
                            if (isServiceRunning) NeonGreen.copy(0.5f) else CardBorder,
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isServiceRunning) NeonGreen else TextMuted)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text          = if (isServiceRunning) "ACTIVE" else "STANDBY",
                            fontSize      = 9.sp,
                            color         = if (isServiceRunning) NeonGreen else TextMuted,
                            letterSpacing = 2.sp,
                            fontFamily    = AppFontFamily
                        )
                    }
                }
            }

            // ── Status bar ────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(CardBg)
                    .border(0.5.dp, CardBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier          = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text       = "> ",
                        fontSize   = 12.sp,
                        color      = NeonGreen.copy(0.7f),
                        fontFamily = AppFontFamily
                    )
                    Text(
                        text       = if (isServiceRunning) "LISTENING  SAY \"AXON\" TO ACTIVATE"
                        else "SYSTEM IDLE  TAP START TO BEGIN",
                        fontSize   = 10.sp,
                        color      = TextMuted,
                        letterSpacing = 1.sp,
                        fontFamily = AppFontFamily,
                        modifier   = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    if (isServiceRunning) {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            barHeights.forEach { anim ->
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height((18 * anim.value).dp)
                                        .clip(RoundedCornerShape(1.dp))
                                        .background(NeonGreen.copy(alpha = 0.8f))
                                )
                            }
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            listOf(6, 10, 16, 10, 6).forEach { h ->
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(h.dp)
                                        .clip(RoundedCornerShape(1.dp))
                                        .background(CardBorder2)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            // ── Central HUD Button ────────────────────────────────
            if (!permissionsGranted) {
                Box(
                    modifier        = Modifier.size(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(160.dp)
                            .clip(CircleShape)
                            .border(1.dp, CardBorder, CircleShape)
                            .background(CardBg)
                            .clickable { onRequestPerms() },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = null,
                                tint     = NeonGreen,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "ALLOW",
                                fontSize      = 10.sp,
                                color         = TextPrimary,
                                fontWeight    = FontWeight.Bold,
                                letterSpacing = 3.sp,
                                fontFamily    = AppFontFamily
                            )
                        }
                    }
                }
            } else {
                Box(
                    modifier        = Modifier.size(240.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Outer orbit ring 1
                    if (isServiceRunning) {
                        Canvas(modifier = Modifier.size(220.dp)) {
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val r  = size.minDimension / 2f - 2.dp.toPx()

                            // Dashed orbit arc
                            drawArc(
                                color      = NeonGreen.copy(alpha = 0.25f),
                                startAngle = orbitAngle,
                                sweepAngle = 270f,
                                useCenter  = false,
                                style      = Stroke(width = 1.dp.toPx(), pathEffect =
                                    androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                        floatArrayOf(6f, 8f)
                                    )
                                ),
                                topLeft = Offset(cx - r, cy - r),
                                size    = androidx.compose.ui.geometry.Size(r * 2, r * 2)
                            )

                            // Orbit dot
                            val dotAngleRad = Math.toRadians(orbitAngle.toDouble())
                            drawCircle(
                                color  = NeonGreen.copy(alpha = 0.9f),
                                radius = 4.dp.toPx(),
                                center = Offset(
                                    cx + (r * kotlin.math.cos(dotAngleRad)).toFloat(),
                                    cy + (r * kotlin.math.sin(dotAngleRad)).toFloat()
                                )
                            )
                        }

                        // Inner orbit ring 2
                        Canvas(modifier = Modifier.size(185.dp)) {
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val r  = size.minDimension / 2f - 2.dp.toPx()
                            drawArc(
                                color      = NeonCyan.copy(alpha = 0.2f),
                                startAngle = orbitAngle2,
                                sweepAngle = 200f,
                                useCenter  = false,
                                style      = Stroke(width = 0.8.dp.toPx(), pathEffect =
                                    androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                        floatArrayOf(4f, 10f)
                                    )
                                ),
                                topLeft = Offset(cx - r, cy - r),
                                size    = androidx.compose.ui.geometry.Size(r * 2, r * 2)
                            )
                            val dotAngleRad = Math.toRadians(orbitAngle2.toDouble())
                            drawCircle(
                                color  = NeonCyan.copy(alpha = 0.8f),
                                radius = 3.dp.toPx(),
                                center = Offset(
                                    cx + (r * kotlin.math.cos(dotAngleRad)).toFloat(),
                                    cy + (r * kotlin.math.sin(dotAngleRad)).toFloat()
                                )
                            )
                        }
                    }

                    // Pulse ring
                    if (isServiceRunning) {
                        Box(
                            modifier = Modifier
                                .size(155.dp)
                                .scale(pulseScale)
                                .drawBehind {
                                    drawCircle(
                                        color  = NeonGreen.copy(alpha = pulseAlpha * 0.3f),
                                        radius = size.minDimension / 2f
                                    )
                                    drawCircle(
                                        color  = NeonGreen.copy(alpha = pulseAlpha),
                                        radius = size.minDimension / 2f,
                                        style  = Stroke(width = 1.dp.toPx())
                                    )
                                }
                        )
                    }

                    // Main button — circle with HUD cuts
                    Box(
                        modifier = Modifier
                            .size(140.dp)
                            .drawBehind {
                                val cx    = size.width / 2f
                                val cy    = size.height / 2f
                                val r     = size.minDimension / 2f
                                val color = if (isServiceRunning) NeonGreen else NeonCyan

                                // Background fill
                                drawCircle(
                                    color  = if (isServiceRunning)
                                        NeonGreen.copy(alpha = 0.08f)
                                    else CardBg,
                                    radius = r
                                )
                                // Outer ring
                                drawCircle(
                                    color  = color.copy(alpha = 0.6f),
                                    radius = r,
                                    style  = Stroke(width = 1.5.dp.toPx())
                                )
                                // Inner ring
                                drawCircle(
                                    color  = color.copy(alpha = 0.25f),
                                    radius = r - 8.dp.toPx(),
                                    style  = Stroke(width = 0.5.dp.toPx())
                                )
                                // Corner HUD ticks (4 positions)
                                val tickLen = 12.dp.toPx()
                                val tickGap = 6.dp.toPx()
                                val tickPaint = Paint().apply {
                                    this.color = color.copy(alpha = 0.8f).toArgb()
                                    strokeWidth = 2.dp.toPx()
                                }
                                val nativeCanvas = drawContext.canvas.nativeCanvas
                                listOf(0f, 90f, 180f, 270f).forEach { angle ->
                                    val rad = Math.toRadians(angle.toDouble())
                                    val sx  = cx + ((r - tickGap) * kotlin.math.cos(rad)).toFloat()
                                    val sy  = cy + ((r - tickGap) * kotlin.math.sin(rad)).toFloat()
                                    val ex  = cx + ((r - tickGap - tickLen) * kotlin.math.cos(rad)).toFloat()
                                    val ey  = cy + ((r - tickGap - tickLen) * kotlin.math.sin(rad)).toFloat()
                                    nativeCanvas.drawLine(sx, sy, ex, ey, tickPaint)
                                }
                            }
                            .clip(CircleShape)
                            .clickable {
                                if (isServiceRunning) onStopClick() else onStartClick()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text       = if (isServiceRunning) "■" else "▶",
                                fontSize   = 30.sp,
                                color      = if (isServiceRunning) NeonGreen else NeonCyan,
                                fontFamily = AppFontFamily
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text          = if (isServiceRunning) "STOP" else "START",
                                fontSize      = 9.sp,
                                color         = if (isServiceRunning) NeonGreen else TextMuted,
                                fontWeight    = FontWeight.Bold,
                                letterSpacing = 3.sp,
                                fontFamily    = AppFontFamily
                            )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    text          = if (isServiceRunning) "SYS :: MONITORING" else "SYS :: IDLE",
                    fontSize      = 9.sp,
                    color         = if (isServiceRunning) NeonGreen.copy(0.7f) else TextMuted,
                    letterSpacing = 2.sp,
                    fontFamily    = AppFontFamily
                )
            }

            Spacer(Modifier.weight(1f))

            // ── Stats Row ─────────────────────────────────────────
            if (permissionsGranted) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HudStatCard(
                        modifier = Modifier.weight(1f),
                        label    = "QUERIES",
                        value    = "${ServiceStatsTracker.queryCount}"
                    )
                    HudStatCard(
                        modifier = Modifier.weight(1f),
                        label    = "AVG RESP",
                        value    = ServiceStatsTracker.avgResponseString()
                    )
                    HudStatCard(
                        modifier  = Modifier.weight(1f),
                        label     = "UPLINK",
                        value     = "",
                        dot       = ServiceStatsTracker.isOnline,
                        dotColor  = if (ServiceStatsTracker.isOnline) NeonGreen else TextMuted
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            // ── Bottom Nav ────────────────────────────────────────
            AxonAxonBottomNav(
                selectedTab      = 0,
                isServiceRunning = isServiceRunning,
                onListenClick    = {},
                onSettingsClick  = onOpenSettings,
                onChatClick      = onOpenChat,
                onWidgetClick    = onOpenWidget,
                onPairClick      = onOpenPair
            )
        }
    }
}

@Composable
private fun HudStatCard(
    modifier: Modifier  = Modifier,
    label:    String,
    value:    String    = "",
    dot:      Boolean?  = null,
    dotColor: Color     = NeonGreen
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(CardBg)
            .border(0.5.dp, CardBorder, RoundedCornerShape(6.dp))
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text          = label,
                fontSize      = 7.sp,
                color         = TextMuted,
                letterSpacing = 1.5.sp,
                fontFamily    = AppFontFamily
            )
            Spacer(Modifier.height(6.dp))
            if (dot != null) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            } else {
                Text(
                    text       = value,
                    fontSize   = 16.sp,
                    color      = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontFamily = AppFontFamily
                )
            }
        }
    }
}

@Composable
internal fun AxonAxonBottomNav(
    selectedTab:      Int,
    isServiceRunning: Boolean,
    onListenClick:    () -> Unit,
    onSettingsClick:  () -> Unit,
    onChatClick:      () -> Unit = {},
    onWidgetClick:    () -> Unit = {},
    onPairClick:      () -> Unit = {},
    modifier:         Modifier  = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(BgSecondary)
            .border(0.5.dp, CardBorder, RoundedCornerShape(0.dp))
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        HudNavItem("LISTEN",   selectedTab == 0, Icons.Default.Mic,      onClick = onListenClick)
        HudNavItem(
            label    = "CHAT",
            selected = selectedTab == 3,
            icon     = Icons.Default.Chat,
            isDimmed = isServiceRunning,
            onClick  = {
                if (isServiceRunning)
                    Toast.makeText(context, "Stop voice first", Toast.LENGTH_SHORT).show()
                else onChatClick()
            }
        )
        HudNavItem(
            label    = "PAIR",
            selected = selectedTab == 5,
            icon     = Icons.Default.Article,
            isDimmed = isServiceRunning,
            onClick  = {
                if (isServiceRunning)
                    Toast.makeText(context, "Stop voice first", Toast.LENGTH_SHORT).show()
                else onPairClick()
            }
        )
        HudNavItem("CONFIG",   selectedTab == 1, Icons.Default.Settings,  onClick = onSettingsClick)
        HudNavItem("WIDGET",   selectedTab == 4, Icons.Default.GridView,  onClick = onWidgetClick)
    }
}

@Composable
private fun HudNavItem(
    label:    String,
    selected: Boolean,
    icon:     androidx.compose.ui.graphics.vector.ImageVector,
    isDimmed: Boolean = false,
    onClick:  () -> Unit
) {
    val activeColor = NeonGreen
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top indicator line
        Box(
            modifier = Modifier
                .width(20.dp)
                .height(1.5.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(
                    if (selected) activeColor
                    else Color.Transparent
                )
        )
        Spacer(Modifier.height(6.dp))
        Icon(
            imageVector        = icon,
            contentDescription = label,
            tint               = when {
                selected -> activeColor
                isDimmed -> CardBorder2.copy(alpha = 0.4f)
                else     -> TextMuted
            },
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text          = label,
            fontSize      = 7.sp,
            color         = when {
                selected -> activeColor
                isDimmed -> TextMuted.copy(alpha = 0.35f)
                else     -> TextMuted
            },
            fontWeight    = if (selected) FontWeight.Bold else FontWeight.Normal,
            letterSpacing = 1.sp,
            fontFamily    = AppFontFamily
        )
    }
}

// ══════════════════════════════════════════════════════════════════════
//  SETTINGS SCREEN
// ══════════════════════════════════════════════════════════════════════

@Composable
fun SettingsScreen(
    isServiceRunning:         Boolean,
    presetEndpoints:          List<String>,
    selectedEndpoint:         String,
    customEndpoint:           String,
    diagnosticResult:         DiagnosticResult?,
    showDiagnostics:          Boolean,
    overlayPermGranted:       Boolean,
    permissionsGranted:       Boolean,
    currentMode:              AxonMode,
    modelsStatus:             MainActivity.ModelsStatus,
    ttsEnginesList:           List<TtsEngineLister.TtsEngineInfo>,
    onModeChange:             (AxonMode) -> Unit,
    onInitLocalModels:        () -> Unit,
    onSelectedEndpointChange: (String) -> Unit,
    onOpenPair:               () -> Unit = {},  // ← جديد
    onCustomEndpointChange:   (String) -> Unit,
    onRequestOverlay:         () -> Unit,
    onRunDiagnostics:         () -> Unit,
    onToggleDiagnostics:      () -> Unit,
    onViewErrorLog:           () -> Unit,
    onOpenChat:               () -> Unit,
    onOpenWidget:             () -> Unit,
    onBack:                   () -> Unit,
    selectedAndroidTtsEnginePkg: String = "", // ← أضف ده
    onAndroidTtsEngineSelected: (String) -> Unit = {}, // ← وده
    currentTtsEngine: TtsEngineType = TtsEngineType.ANDROID_TTS,
    supertonicSid: Int = 6,
    supertonicSpeed: Float = 1.25f,
    supertonicNumSteps: Int = 8,
    vitsSid: Int = 0,
    vitsSpeed: Float = 1.0f,
    vitsSilenceScale: Float = 0.2f,
    customModelDir: String = "",
    onTtsEngineChange: (TtsEngineType) -> Unit = {},
    onSupertonicConfigChange: (Int, Float, Int) -> Unit = { _, _, _ -> },
    onVitsConfigChange: (Int, Float, Float) -> Unit = { _, _, _ -> },
    onCustomModelDirChange: (String) -> Unit = {},
    currentLocalProvider: LocalLlmProviderType = LocalLlmProviderType.GEMMA_4B,
    cohereApiKey: String = "",
    cohereModel: String = "command-a-plus-05-2026",
    dahlApiKey: String = "",
    dahlModel: String = "MiniMaxAI/MiniMax-M2.7",
    onLocalProviderChange: (LocalLlmProviderType) -> Unit = {},
    onCohereApiKeyChange: (String) -> Unit = {},
    onCohereModelChange: (String) -> Unit = {},
    onDahlApiKeyChange: (String) -> Unit = {},
    onDahlModelChange: (String) -> Unit = {},
    currentSttMode: SttMode = SttMode.LOCAL,
    deepgramApiKey: String = "",
    onSttModeChange: (SttMode) -> Unit = {},
    onDeepgramApiKeyChange: (String) -> Unit = {},
    onOpenNotificationRules: () -> Unit = {},
) {
    val CUSTOM_LABEL = "Custom…"
    var draftCustom  by remember { mutableStateOf(customEndpoint) }
    val context = androidx.compose.ui.platform.LocalContext.current


    Box(modifier = Modifier.fillMaxSize().background(BgPrimary)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // ── Header ───────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(CardBg)
                        .border(0.5.dp, CardBorder, RoundedCornerShape(6.dp))
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back",
                        tint = TextPrimary, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text          = "CONFIG",
                        fontSize      = 18.sp,
                        fontWeight    = FontWeight.Bold,
                        color         = TextPrimary,
                        letterSpacing = 4.sp,
                        fontFamily    = AppFontFamily
                    )
                    Text(
                        text          = "SYSTEM SETTINGS",
                        fontSize      = 8.sp,
                        color         = TextMuted,
                        letterSpacing = 2.sp,
                        fontFamily    = AppFontFamily
                    )
                }
            }

            // ── BACKEND ───────────────────────────────────────────
            HudCollapsibleCard(
                title = "BACKEND :: NODE SELECT",
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                // Server Connect Toggle Button
                val serverConnectEnabled = prefs.getBoolean(PREF_SERVER_CONNECT_ENABLED, false)
                Box(modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (serverConnectEnabled) NeonGreen.copy(0.12f) else AccentAmber.copy(0.12f))
                    .border(0.5.dp, if (serverConnectEnabled) NeonGreen.copy(0.6f) else AccentAmber.copy(0.6f), RoundedCornerShape(6.dp))
                    .clickable { 
                        prefs.edit().putBoolean(PREF_SERVER_CONNECT_ENABLED, !serverConnectEnabled).apply()
                        Toast.makeText(ctx, if (!serverConnectEnabled) "Server ON - will connect on next start" else "Server OFF - disconnected", Toast.LENGTH_SHORT).show()
                        // Restart service if running to apply changes
                        if (isServiceRunning) {
                            stopListeningService()
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                startListeningService()
                            }, 500)
                        }
                    }
                    .padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (serverConnectEnabled) androidx.compose.material.icons.Icons.Default.CheckCircle else androidx.compose.material.icons.Icons.Default.Cancel,
                            contentDescription = null,
                            tint = if (serverConnectEnabled) NeonGreen else AccentAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (serverConnectEnabled) "⟳ TURN OFF SERVER" else "⏻ TURN ON SERVER",
                            fontSize = 10.sp,
                            color = if (serverConnectEnabled) NeonGreen else AccentAmber,
                            letterSpacing = 1.2.sp,
                            fontFamily = AppFontFamily
                        )
                    }
                }
                
                Spacer(Modifier.height(10.dp))
                
                if (!serverConnectEnabled) {
                    Text("⚠️ Server is OFF - endpoint selection has no effect until enabled", 
                        fontSize = 7.sp, color = AccentAmber.copy(0.8f),
                        letterSpacing = 0.5.sp, fontFamily = AppFontFamily)
                    Spacer(Modifier.height(8.dp))
                }
                
                presetEndpoints.forEachIndexed { idx, ep ->
                    HudEndpointRow(
                        label    = ep,
                        selected = selectedEndpoint == ep,
                        isActive = selectedEndpoint == ep && serverConnectEnabled,
                        onClick  = { if (serverConnectEnabled) onSelectedEndpointChange(ep) }
                    )
                    if (idx < presetEndpoints.size - 1) {
                        Divider(color = CardBorder, thickness = 0.5.dp)
                    }
                }
                Divider(color = CardBorder, thickness = 0.5.dp)
                HudEndpointRow(
                    label    = "CUSTOM NODE",
                    selected = selectedEndpoint == CUSTOM_LABEL,
                    isActive = false,
                    onClick  = { onSelectedEndpointChange(CUSTOM_LABEL) },
                    trailing = {
                        if (!overlayPermGranted) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(AccentAmber.copy(0.12f))
                                    .border(0.5.dp, AccentAmber.copy(0.5f), RoundedCornerShape(4.dp))
                                    .clickable { onRequestOverlay() }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    "GRANT",
                                    fontSize      = 8.sp,
                                    color         = AccentAmber,
                                    letterSpacing = 1.sp,
                                    fontFamily    = AppFontFamily
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(NeonGreen.copy(0.1f))
                                    .border(0.5.dp, NeonGreen.copy(0.5f), RoundedCornerShape(3.dp))
                            )
                        }
                    }
                )
                if (selectedEndpoint == CUSTOM_LABEL) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value         = draftCustom,
                        onValueChange = { draftCustom = it },
                        label         = {
                            Text(
                                "ENDPOINT URL",
                                fontSize      = 9.sp,
                                color         = TextMuted,
                                letterSpacing = 1.sp,
                                fontFamily    = AppFontFamily
                            )
                        },
                        singleLine = true,
                        modifier   = Modifier.fillMaxWidth(),
                        colors     = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = NeonGreen,
                            unfocusedBorderColor = CardBorder,
                            focusedLabelColor    = NeonGreen,
                            cursorColor          = NeonGreen,
                            focusedTextColor     = TextPrimary,
                            unfocusedTextColor   = TextPrimary
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily    = AppFontFamily,
                            fontSize      = 12.sp,
                            letterSpacing = 0.5.sp
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(NeonGreen.copy(0.1f))
                            .border(0.5.dp, NeonGreen.copy(0.5f), RoundedCornerShape(6.dp))
                            .clickable { onCustomEndpointChange(draftCustom) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "SAVE NODE",
                            fontSize      = 10.sp,
                            color         = NeonGreen,
                            fontWeight    = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            fontFamily    = AppFontFamily
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // ── LOCAL LLM PROVIDER ───────────────────────────────────────────
            HudCollapsibleCard(
                title = "LOCAL LLM PROVIDER :: ENGINE SELECT",
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Text(
                    "INFERENCE ENGINE",
                    fontSize      = 8.sp,
                    color         = TextMuted,
                    letterSpacing = 1.5.sp,
                    fontFamily    = AppFontFamily
                )
                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val providerList = listOf(
                        LocalLlmProviderType.GEMMA_4B to "◈ GEMMA 4B",
                        LocalLlmProviderType.COHERE_API to "◉ COHERE API",
                        LocalLlmProviderType.DAHL_API to "◉ DAHL API"
                    )
                    providerList.forEach { (provider, label) ->
                        val isSelected = currentLocalProvider == provider
                        val accent = when (provider) {
                            LocalLlmProviderType.GEMMA_4B -> NeonGreen
                            LocalLlmProviderType.COHERE_API -> AccentPink
                            LocalLlmProviderType.DAHL_API -> NeonCyan
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) accent.copy(0.12f) else CardBg)
                                .border(
                                    0.5.dp,
                                    if (isSelected) accent.copy(0.6f) else CardBorder,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable(enabled = !isServiceRunning || currentMode != AxonMode.LOCAL) {
                                    onLocalProviderChange(provider)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                fontSize      = 10.sp,
                                color         = if (isSelected) accent else TextMuted,
                                letterSpacing = 1.sp,
                                fontFamily    = AppFontFamily
                            )
                        }
                    }
                }

                // Cohere config UI
                if (currentLocalProvider == LocalLlmProviderType.COHERE_API) {
                    Spacer(Modifier.height(16.dp))
                    Divider(color = CardBorder, thickness = 0.5.dp)
                    Spacer(Modifier.height(16.dp))

                    Text(
                        "COHERE API CONFIGURATION",
                        fontSize      = 8.sp,
                        color         = AccentPink.copy(0.8f),
                        letterSpacing = 1.5.sp,
                        fontFamily    = AppFontFamily
                    )
                    Spacer(Modifier.height(12.dp))

                    var draftApiKey by remember { mutableStateOf(cohereApiKey) }
                    OutlinedTextField(
                        value         = draftApiKey,
                        onValueChange = { draftApiKey = it },
                        label         = {
                            Text(
                                "API KEY",
                                fontSize      = 9.sp,
                                color         = TextMuted,
                                letterSpacing = 1.sp,
                                fontFamily    = AppFontFamily
                            )
                        },
                        singleLine = true,
                        modifier   = Modifier.fillMaxWidth(),
                        colors     = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = AccentPink,
                            unfocusedBorderColor = CardBorder,
                            focusedLabelColor    = AccentPink,
                            cursorColor          = AccentPink,
                            focusedTextColor     = TextPrimary,
                            unfocusedTextColor   = TextPrimary
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily    = AppFontFamily,
                            fontSize      = 12.sp,
                            letterSpacing = 0.5.sp
                        )
                    )
                    Spacer(Modifier.height(8.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(AccentPink.copy(0.1f))
                            .border(0.5.dp, AccentPink.copy(0.5f), RoundedCornerShape(6.dp))
                            .clickable { onCohereApiKeyChange(draftApiKey) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "SAVE API KEY",
                            fontSize      = 10.sp,
                            color         = AccentPink,
                            fontWeight    = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            fontFamily    = AppFontFamily
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        "MODEL",
                        fontSize      = 8.sp,
                        color         = TextMuted,
                        letterSpacing = 1.5.sp,
                        fontFamily    = AppFontFamily
                    )
                    Spacer(Modifier.height(6.dp))

                    var modelExpanded by remember { mutableStateOf(false) }
                    Box {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(CardBg)
                                .border(0.5.dp, AccentPink.copy(0.4f), RoundedCornerShape(6.dp))
                                .clickable { modelExpanded = true },
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment     = Alignment.CenterVertically
                            ) {
                                Text(
                                    "◈ $cohereModel",
                                    fontSize      = 10.sp,
                                    color         = AccentPink,
                                    letterSpacing = 1.sp,
                                    fontFamily    = AppFontFamily
                                )
                                Text(
                                    "▾",
                                    fontSize = 10.sp,
                                    color    = AccentPink
                                )
                            }
                        }

                        DropdownMenu(
                            expanded         = modelExpanded,
                            onDismissRequest = { modelExpanded = false },
                            modifier         = Modifier
                                .background(BgSecondary)
                                .border(0.5.dp, CardBorder, RoundedCornerShape(6.dp))
                        ) {
                            CohereLlmProvider.AVAILABLE_MODELS.forEach { model ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "◈ $model",
                                            fontSize      = 10.sp,
                                            color         = if (model == cohereModel) AccentPink else TextPrimary,
                                            letterSpacing = 1.sp,
                                            fontFamily    = AppFontFamily
                                        )
                                    },
                                    onClick = {
                                        modelExpanded = false
                                        onCohereModelChange(model)
                                    }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    val cohereProvider = remember { CohereLlmProvider(context) }
                    val keyStatus = if (cohereProvider.hasApiKey()) "✓ KEY CONFIGURED" else "✗ KEY MISSING"
                    val keyColor = if (cohereProvider.hasApiKey()) NeonGreen else AccentPink
                    Text(
                        keyStatus,
                        fontSize      = 8.sp,
                        color         = keyColor.copy(0.7f),
                        letterSpacing = 1.sp,
                        fontFamily    = AppFontFamily
                    )
                }

                // Dahl config UI
                if (currentLocalProvider == LocalLlmProviderType.DAHL_API) {
                    Spacer(Modifier.height(16.dp))
                    Divider(color = CardBorder, thickness = 0.5.dp)
                    Spacer(Modifier.height(16.dp))

                    Text(
                        "DAHL API CONFIGURATION",
                        fontSize      = 8.sp,
                        color         = NeonCyan.copy(0.8f),
                        letterSpacing = 1.5.sp,
                        fontFamily    = AppFontFamily
                    )
                    Spacer(Modifier.height(12.dp))

                    var draftApiKey by remember { mutableStateOf(dahlApiKey) }
                    OutlinedTextField(
                        value         = draftApiKey,
                        onValueChange = { draftApiKey = it },
                        label         = {
                            Text(
                                "API KEY",
                                fontSize      = 9.sp,
                                color         = TextMuted,
                                letterSpacing = 1.sp,
                                fontFamily    = AppFontFamily
                            )
                        },
                        singleLine = true,
                        modifier   = Modifier.fillMaxWidth(),
                        colors     = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = NeonCyan,
                            unfocusedBorderColor = CardBorder,
                            focusedLabelColor    = NeonCyan,
                            cursorColor          = NeonCyan,
                            focusedTextColor     = TextPrimary,
                            unfocusedTextColor   = TextPrimary
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily    = AppFontFamily,
                            fontSize      = 12.sp,
                            letterSpacing = 0.5.sp
                        )
                    )
                    Spacer(Modifier.height(8.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(NeonCyan.copy(0.1f))
                            .border(0.5.dp, NeonCyan.copy(0.5f), RoundedCornerShape(6.dp))
                            .clickable { onDahlApiKeyChange(draftApiKey) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "SAVE API KEY",
                            fontSize      = 10.sp,
                            color         = NeonCyan,
                            fontWeight    = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            fontFamily    = AppFontFamily
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        "MODEL",
                        fontSize      = 8.sp,
                        color         = TextMuted,
                        letterSpacing = 1.5.sp,
                        fontFamily    = AppFontFamily
                    )
                    Spacer(Modifier.height(6.dp))

                    var modelExpanded by remember { mutableStateOf(false) }
                    Box {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(CardBg)
                                .border(0.5.dp, NeonCyan.copy(0.4f), RoundedCornerShape(6.dp))
                                .clickable { modelExpanded = true },
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment     = Alignment.CenterVertically
                            ) {
                                Text(
                                    "◈ $dahlModel",
                                    fontSize      = 10.sp,
                                    color         = NeonCyan,
                                    letterSpacing = 1.sp,
                                    fontFamily    = AppFontFamily
                                )
                                Text(
                                    "▾",
                                    fontSize = 10.sp,
                                    color    = NeonCyan
                                )
                            }
                        }

                        DropdownMenu(
                            expanded         = modelExpanded,
                            onDismissRequest = { modelExpanded = false },
                            modifier         = Modifier
                                .background(BgSecondary)
                                .border(0.5.dp, CardBorder, RoundedCornerShape(6.dp))
                        ) {
                            DahlLlmProvider.AVAILABLE_MODELS.forEach { model ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "◈ $model",
                                            fontSize      = 10.sp,
                                            color         = if (model == dahlModel) NeonCyan else TextPrimary,
                                            letterSpacing = 1.sp,
                                            fontFamily    = AppFontFamily
                                        )
                                    },
                                    onClick = {
                                        modelExpanded = false
                                        onDahlModelChange(model)
                                    }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    val dahlProviderCheck = remember { DahlLlmProvider(context) }
                    val dahlKeyStatus = if (dahlProviderCheck.hasApiKey()) "✓ KEY CONFIGURED" else "✗ KEY MISSING"
                    val dahlKeyColor = if (dahlProviderCheck.hasApiKey()) NeonGreen else NeonCyan
                    Text(
                        dahlKeyStatus,
                        fontSize      = 8.sp,
                        color         = dahlKeyColor.copy(0.7f),
                        letterSpacing = 1.sp,
                        fontFamily    = AppFontFamily
                    )
                }

                if (isServiceRunning && currentMode == AxonMode.LOCAL) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "// STOP SERVICE TO CHANGE PROVIDER",
                        fontSize      = 7.sp,
                        color         = TextMuted.copy(0.6f),
                        letterSpacing = 1.sp,
                        fontFamily    = AppFontFamily
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── TTS ENGINE SELECTION ─────────────────────────────────────────────
            HudCollapsibleCard(
                title = "TTS ENGINE :: VOICE SYNTHESIS",
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Text("SYNTHESIS ENGINE", fontSize = 8.sp, color = TextMuted,
                    letterSpacing = 1.5.sp, fontFamily = AppFontFamily)
                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        TtsEngineType.ANDROID_TTS to "◈ ANDROID",
                        TtsEngineType.SHERPA_SUPERTONIC to "◉ SUPERTONIC",
                        TtsEngineType.SHERPA_VITS_PIPER to "◉ VITS PIPER",
                        TtsEngineType.DEEPGRAM_TTS to "◉ DEEPGRAM"
                    ).forEach { (engine, label) ->
                        val isSelected = currentTtsEngine == engine
                        val accent = when (engine) {
                            TtsEngineType.ANDROID_TTS -> NeonCyan
                            TtsEngineType.SHERPA_SUPERTONIC -> NeonGreen
                            TtsEngineType.SHERPA_VITS_PIPER -> AccentPink
                            TtsEngineType.DEEPGRAM_TTS -> AccentAmber
                        }
                        Box(
                            modifier = Modifier.weight(1f).height(38.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) accent.copy(0.12f) else CardBg)
                                .border(0.5.dp, if (isSelected) accent.copy(0.6f) else CardBorder, RoundedCornerShape(6.dp))
                                .clickable { onTtsEngineChange(engine) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, fontSize = 9.sp, color = if (isSelected) accent else TextMuted,
                                letterSpacing = 0.5.sp, fontFamily = AppFontFamily)
                        }
                    }
                }

                // Status
                Spacer(Modifier.height(8.dp))
                val engineStatus = when (currentTtsEngine) {
                    TtsEngineType.ANDROID_TTS ->
                        if (modelsStatus.tts) "✓ ENGINES READY (${ttsEnginesList.size} found)" else "✗ NO TTS ENGINES"
                    TtsEngineType.SHERPA_SUPERTONIC ->
                        if (modelsStatus.sherpaModelsReady) "✓ SUPERTONIC MODELS READY" else "✗ MODELS MISSING"
                    TtsEngineType.SHERPA_VITS_PIPER ->
                        if (modelsStatus.sherpaModelsReady) "✓ VITS MODELS READY" else "✗ MODELS MISSING"
                    TtsEngineType.DEEPGRAM_TTS -> {
                        val deepgramKey = prefs.getString(PREF_DEEPGRAM_TTS_KEY, "") ?: ""
                        if (deepgramKey.isNotBlank()) "✓ DEEPGRAM API KEY SET" else "✗ API KEY MISSING"
                    }
                }
                Text(engineStatus, fontSize = 8.sp,
                    color = if ((currentTtsEngine == TtsEngineType.ANDROID_TTS && modelsStatus.tts) ||
                        (currentTtsEngine != TtsEngineType.ANDROID_TTS && currentTtsEngine != TtsEngineType.DEEPGRAM_TTS && modelsStatus.sherpaModelsReady) ||
                        (currentTtsEngine == TtsEngineType.DEEPGRAM_TTS && (prefs.getString(PREF_DEEPGRAM_TTS_KEY, "") ?: "").isNotBlank()))
                        NeonGreen.copy(0.7f) else AccentPink.copy(0.7f),
                    letterSpacing = 1.sp, fontFamily = AppFontFamily)

                // Deepgram Config (API Key + Voice)
                if (currentTtsEngine == TtsEngineType.DEEPGRAM_TTS) {
                    Spacer(Modifier.height(12.dp))
                    Divider(color = CardBorder, thickness = 0.5.dp)
                    Spacer(Modifier.height(12.dp))

                    Text("DEEPGRAM API CONFIG", fontSize = 8.sp, color = AccentAmber.copy(0.8f),
                        letterSpacing = 1.5.sp, fontFamily = AppFontFamily)
                    Spacer(Modifier.height(6.dp))

                    var draftApiKey by remember { mutableStateOf(prefs.getString(PREF_DEEPGRAM_TTS_KEY, "") ?: "") }
                    OutlinedTextField(
                        value = draftApiKey,
                        onValueChange = { draftApiKey = it },
                        label = { Text("API KEY", fontSize = 8.sp,
                            color = TextMuted, fontFamily = AppFontFamily) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentAmber, unfocusedBorderColor = CardBorder,
                            focusedLabelColor = AccentAmber, cursorColor = AccentAmber,
                            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = AppFontFamily, fontSize = 10.sp)
                    )
                    
                    Spacer(Modifier.height(6.dp))
                    
                    var draftVoice by remember { mutableStateOf(prefs.getString(PREF_DEEPGRAM_TTS_VOICE, "aura-2-en-daniel") ?: "aura-2-en-daniel") }
                    OutlinedTextField(
                        value = draftVoice,
                        onValueChange = { draftVoice = it },
                        label = { Text("VOICE (e.g., aura-2-en-daniel, aurora, olive, aria)", fontSize = 8.sp,
                            color = TextMuted, fontFamily = AppFontFamily) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentAmber, unfocusedBorderColor = CardBorder,
                            focusedLabelColor = AccentAmber, cursorColor = AccentAmber,
                            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = AppFontFamily, fontSize = 10.sp)
                    )
                    
                    Spacer(Modifier.height(6.dp))
                    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                        .background(AccentAmber.copy(0.1f))
                        .border(0.5.dp, AccentAmber.copy(0.5f), RoundedCornerShape(4.dp))
                        .clickable { 
                            prefs.edit()
                                .putString(PREF_DEEPGRAM_TTS_KEY, draftApiKey)
                                .putString(PREF_DEEPGRAM_TTS_VOICE, draftVoice)
                                .apply()
                            Toast.makeText(ctx, "Deepgram config saved", Toast.LENGTH_SHORT).show()
                        }
                        .padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text("SAVE DEEPGRAM CONFIG", fontSize = 9.sp, color = AccentAmber,
                            letterSpacing = 1.sp, fontFamily = AppFontFamily)
                    }
                    
                    Spacer(Modifier.height(4.dp))
                    Text("// Voices: aura-2-en-daniel, aurora, olive, aria, nova, jupiter, etc.", fontSize = 7.sp, color = TextMuted.copy(0.5f),
                        fontFamily = AppFontFamily)
                }

                // Custom Model Path (لأي Sherpa engine)
                if (currentTtsEngine != TtsEngineType.ANDROID_TTS) {
                    Spacer(Modifier.height(12.dp))
                    Divider(color = CardBorder, thickness = 0.5.dp)
                    Spacer(Modifier.height(12.dp))

                    Text("CUSTOM MODEL PATH", fontSize = 8.sp, color = NeonCyan.copy(0.8f),
                        letterSpacing = 1.5.sp, fontFamily = AppFontFamily)
                    Spacer(Modifier.height(6.dp))

                    var draftDir by remember { mutableStateOf(customModelDir) }
                    OutlinedTextField(
                        value = draftDir,
                        onValueChange = { draftDir = it },
                        label = { Text("MODEL DIRECTORY (leave empty for default)", fontSize = 8.sp,
                            color = TextMuted, fontFamily = AppFontFamily) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonGreen, unfocusedBorderColor = CardBorder,
                            focusedLabelColor = NeonGreen, cursorColor = NeonGreen,
                            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = AppFontFamily, fontSize = 10.sp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                        .background(NeonGreen.copy(0.1f))
                        .border(0.5.dp, NeonGreen.copy(0.5f), RoundedCornerShape(4.dp))
                        .clickable { onCustomModelDirChange(draftDir) }
                        .padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text("APPLY PATH", fontSize = 9.sp, color = NeonGreen,
                            letterSpacing = 1.sp, fontFamily = AppFontFamily)
                    }

                    val defaultPath = when (currentTtsEngine) {
                        TtsEngineType.SHERPA_SUPERTONIC -> SherpaTtsEngine.DEFAULT_SUPERTONIC_DIR
                        TtsEngineType.SHERPA_VITS_PIPER -> SherpaTtsEngine.DEFAULT_VITS_DIR
                        else -> ""
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("// DEFAULT: $defaultPath", fontSize = 7.sp, color = TextMuted.copy(0.5f),
                        fontFamily = AppFontFamily)
                }

                // Supertonic Config
                if (currentTtsEngine == TtsEngineType.SHERPA_SUPERTONIC) {
                    Spacer(Modifier.height(12.dp))
                    Divider(color = CardBorder, thickness = 0.5.dp)
                    Spacer(Modifier.height(12.dp))

                    Text("SUPERTONIC CONFIG", fontSize = 8.sp, color = NeonGreen.copy(0.8f),
                        letterSpacing = 1.5.sp, fontFamily = AppFontFamily)
                    Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SPEAKER ID", fontSize = 8.sp, color = TextMuted,
                            fontFamily = AppFontFamily, modifier = Modifier.width(80.dp))
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(value = supertonicSid.toString(),
                            onValueChange = { it.toIntOrNull()?.let { sid -> onSupertonicConfigChange(sid, supertonicSpeed, supertonicNumSteps) }},
                            singleLine = true, modifier = Modifier.width(60.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonGreen, unfocusedBorderColor = CardBorder,
                                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = AppFontFamily, fontSize = 10.sp))
                    }
                    Spacer(Modifier.height(6.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SPEED", fontSize = 8.sp, color = TextMuted,
                            fontFamily = AppFontFamily, modifier = Modifier.width(80.dp))
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(value = supertonicSpeed.toString(),
                            onValueChange = { it.toFloatOrNull()?.let { speed -> onSupertonicConfigChange(supertonicSid, speed, supertonicNumSteps) }},
                            singleLine = true, modifier = Modifier.width(60.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonGreen, unfocusedBorderColor = CardBorder,
                                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = AppFontFamily, fontSize = 10.sp))
                    }
                    Spacer(Modifier.height(6.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("NUM STEPS", fontSize = 8.sp, color = TextMuted,
                            fontFamily = AppFontFamily, modifier = Modifier.width(80.dp))
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(value = supertonicNumSteps.toString(),
                            onValueChange = { it.toIntOrNull()?.let { steps -> onSupertonicConfigChange(supertonicSid, supertonicSpeed, steps) }},
                            singleLine = true, modifier = Modifier.width(60.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonGreen, unfocusedBorderColor = CardBorder,
                                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = AppFontFamily, fontSize = 10.sp))
                    }
                }

                // VITS Piper Config
                if (currentTtsEngine == TtsEngineType.SHERPA_VITS_PIPER) {
                    Spacer(Modifier.height(12.dp))
                    Divider(color = CardBorder, thickness = 0.5.dp)
                    Spacer(Modifier.height(12.dp))

                    Text("VITS PIPER CONFIG", fontSize = 8.sp, color = AccentPink.copy(0.8f),
                        letterSpacing = 1.5.sp, fontFamily = AppFontFamily)
                    Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SPEAKER ID", fontSize = 8.sp, color = TextMuted,
                            fontFamily = AppFontFamily, modifier = Modifier.width(80.dp))
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(value = vitsSid.toString(),
                            onValueChange = { it.toIntOrNull()?.let { sid -> onVitsConfigChange(sid, vitsSpeed, vitsSilenceScale) }},
                            singleLine = true, modifier = Modifier.width(60.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentPink, unfocusedBorderColor = CardBorder,
                                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = AppFontFamily, fontSize = 10.sp))
                    }
                    Spacer(Modifier.height(6.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SPEED", fontSize = 8.sp, color = TextMuted,
                            fontFamily = AppFontFamily, modifier = Modifier.width(80.dp))
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(value = vitsSpeed.toString(),
                            onValueChange = { it.toFloatOrNull()?.let { speed -> onVitsConfigChange(vitsSid, speed, vitsSilenceScale) }},
                            singleLine = true, modifier = Modifier.width(60.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentPink, unfocusedBorderColor = CardBorder,
                                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = AppFontFamily, fontSize = 10.sp))
                    }
                    Spacer(Modifier.height(6.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SILENCE", fontSize = 8.sp, color = TextMuted,
                            fontFamily = AppFontFamily, modifier = Modifier.width(80.dp))
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(value = vitsSilenceScale.toString(),
                            onValueChange = { it.toFloatOrNull()?.let { scale -> onVitsConfigChange(vitsSid, vitsSpeed, scale) }},
                            singleLine = true, modifier = Modifier.width(60.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentPink, unfocusedBorderColor = CardBorder,
                                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = AppFontFamily, fontSize = 10.sp))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── STT ENGINE ─────────────────────────────────────────────
            HudCollapsibleCard(
                title = "STT ENGINE :: SPEECH RECOGNITION",
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Text(
                    "RECOGNITION ENGINE",
                    fontSize = 8.sp,
                    color = TextMuted,
                    letterSpacing = 1.5.sp,
                    fontFamily = AppFontFamily
                )
                Spacer(Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        SttMode.LOCAL to "◈ LOCAL (SHERPA)",
                        SttMode.ONLINE to "◉ DEEPGRAM NOVA-3"
                    ).forEach { (mode, label) ->
                        val isSelected = currentSttMode == mode
                        val accent = if (mode == SttMode.LOCAL) NeonGreen else AccentPink
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) accent.copy(0.12f) else CardBg)
                                .border(
                                    0.5.dp,
                                    if (isSelected) accent.copy(0.6f) else CardBorder,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable(enabled = !isServiceRunning || currentMode != AxonMode.LOCAL) {
                                    onSttModeChange(mode)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                fontSize = 10.sp,
                                color = if (isSelected) accent else TextMuted,
                                letterSpacing = 1.sp,
                                fontFamily = AppFontFamily
                            )
                        }
                    }
                }

                if (currentSttMode == SttMode.ONLINE) {
                    Spacer(Modifier.height(16.dp))
                    Divider(color = CardBorder, thickness = 0.5.dp)
                    Spacer(Modifier.height(16.dp))

                    Text(
                        "DEEPGRAM API CONFIGURATION",
                        fontSize = 8.sp,
                        color = AccentPink.copy(0.8f),
                        letterSpacing = 1.5.sp,
                        fontFamily = AppFontFamily
                    )
                    Spacer(Modifier.height(12.dp))

                    var draftKey by remember { mutableStateOf(deepgramApiKey) }
                    OutlinedTextField(
                        value = draftKey,
                        onValueChange = { draftKey = it },
                        label = {
                            Text(
                                "API KEY",
                                fontSize = 9.sp,
                                color = TextMuted,
                                letterSpacing = 1.sp,
                                fontFamily = AppFontFamily
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentPink,
                            unfocusedBorderColor = CardBorder,
                            focusedLabelColor = AccentPink,
                            cursorColor = AccentPink,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = AppFontFamily,
                            fontSize = 12.sp,
                            letterSpacing = 0.5.sp
                        )
                    )
                    Spacer(Modifier.height(8.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(AccentPink.copy(0.1f))
                            .border(0.5.dp, AccentPink.copy(0.5f), RoundedCornerShape(6.dp))
                            .clickable { onDeepgramApiKeyChange(draftKey) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "SAVE API KEY",
                            fontSize = 10.sp,
                            color = AccentPink,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            fontFamily = AppFontFamily
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    val keySaved = deepgramApiKey.isNotBlank()
                    Text(
                        if (keySaved) "✓ API KEY CONFIGURED" else "✗ API KEY MISSING",
                        fontSize = 8.sp,
                        color = if (keySaved) NeonGreen.copy(0.7f) else AccentPink.copy(0.7f),
                        letterSpacing = 1.sp,
                        fontFamily = AppFontFamily
                    )
                }

                if (isServiceRunning && currentMode == AxonMode.LOCAL) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "// STOP SERVICE TO CHANGE STT ENGINE",
                        fontSize = 7.sp,
                        color = TextMuted.copy(0.6f),
                        letterSpacing = 1.sp,
                        fontFamily = AppFontFamily
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── SYSTEM PROMPT EDITOR ──────────────────────────────────────────
            var showPromptEditor by remember { mutableStateOf(false) }
            var draftPrompt by remember { mutableStateOf(SystemPromptManager.getPrompt()) }
            var promptEnabled by remember { mutableStateOf(SystemPromptManager.isEnabled()) }

            HudCollapsibleCard(
                title = "SYSTEM PROMPT :: PERSONALITY",
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "STATUS",
                            fontSize = 8.sp,
                            color = TextMuted,
                            letterSpacing = 1.5.sp,
                            fontFamily = AppFontFamily
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (promptEnabled) "ACTIVE // ${SystemPromptManager.getPrompt().take(30)}..."
                            else "DISABLED",
                            fontSize = 10.sp,
                            color = if (promptEnabled) NeonGreen else TextMuted,
                            fontFamily = AppFontFamily
                        )
                    }

                    // Toggle button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (promptEnabled) NeonGreen.copy(0.1f) else CardBg)
                            .border(
                                0.5.dp,
                                if (promptEnabled) NeonGreen.copy(0.5f) else CardBorder,
                                RoundedCornerShape(4.dp)
                            )
                            .clickable {
                                promptEnabled = !promptEnabled
                                SystemPromptManager.setEnabled(promptEnabled)
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            if (promptEnabled) "ON" else "OFF",
                            fontSize = 8.sp,
                            color = if (promptEnabled) NeonGreen else TextMuted,
                            letterSpacing = 1.sp,
                            fontFamily = AppFontFamily
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    // Edit button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(NeonCyan.copy(0.1f))
                            .border(0.5.dp, NeonCyan.copy(0.5f), RoundedCornerShape(4.dp))
                            .clickable {
                                draftPrompt = SystemPromptManager.getPrompt()
                                showPromptEditor = true
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            "EDIT",
                            fontSize = 8.sp,
                            color = NeonCyan,
                            letterSpacing = 1.sp,
                            fontFamily = AppFontFamily
                        )
                    }
                }
            }

// ── PROMPT EDITOR DIALOG ──────────────────────────────────────────
            if (showPromptEditor) {
                AlertDialog(
                    onDismissRequest = { showPromptEditor = false },
                    containerColor = CardBg,
                    title = {
                        Text(
                            "// EDIT SYSTEM PROMPT",
                            fontSize = 14.sp,
                            color = TextPrimary,
                            letterSpacing = 2.sp,
                            fontFamily = AppFontFamily
                        )
                    },
                    text = {
                        Column {
                            OutlinedTextField(
                                value = draftPrompt,
                                onValueChange = { draftPrompt = it },
                                label = {
                                    Text(
                                        "SYSTEM PROMPT",
                                        fontSize = 9.sp,
                                        color = TextMuted,
                                        fontFamily = AppFontFamily
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = NeonGreen,
                                    unfocusedBorderColor = CardBorder,
                                    focusedLabelColor = NeonGreen,
                                    cursorColor = NeonGreen,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontFamily = AppFontFamily,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp
                                )
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "// This prompt guides the AI personality and behavior",
                                fontSize = 8.sp,
                                color = TextMuted.copy(0.6f),
                                fontFamily = AppFontFamily
                            )
                        }
                    },
                    confirmButton = {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(NeonGreen.copy(0.1f))
                                .border(0.5.dp, NeonGreen.copy(0.5f), RoundedCornerShape(4.dp))
                                .clickable {
                                    SystemPromptManager.setPrompt(draftPrompt)
                                    showPromptEditor = false
                                    Toast.makeText(context, "✅ System prompt saved", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                "SAVE",
                                fontSize = 10.sp,
                                color = NeonGreen,
                                letterSpacing = 1.sp,
                                fontFamily = AppFontFamily
                            )
                        }
                    },
                    dismissButton = {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(CardBg)
                                .border(0.5.dp, CardBorder, RoundedCornerShape(4.dp))
                                .clickable { showPromptEditor = false }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                "CANCEL",
                                fontSize = 10.sp,
                                color = TextMuted,
                                letterSpacing = 1.sp,
                                fontFamily = AppFontFamily
                            )
                        }
                    }
                )
            }


            Spacer(Modifier.height(8.dp))

            // ── OPERATION MODE ────────────────────────────────────
            HudCollapsibleCard(
                title = "OPERATION MODE :: ENGINE SELECT",
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Text(
                    "INFERENCE BACKEND",
                    fontSize      = 8.sp,
                    color         = TextMuted,
                    letterSpacing = 1.5.sp,
                    fontFamily    = AppFontFamily
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(AxonMode.SERVER to "◈ SERVER", AxonMode.LOCAL to "◉ LOCAL").forEach { (mode, label) ->
                        val isSelected = currentMode == mode
                        val accent = if (mode == AxonMode.SERVER) NeonCyan else NeonGreen
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) accent.copy(0.12f) else CardBg)
                                .border(
                                    0.5.dp,
                                    if (isSelected) accent.copy(0.6f) else CardBorder,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable(enabled = !isServiceRunning) { onModeChange(mode) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                fontSize      = 10.sp,
                                color         = if (isSelected) accent else TextMuted,
                                letterSpacing = 1.sp,
                                fontFamily    = AppFontFamily
                            )
                        }
                    }
                }
                if (isServiceRunning) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "// STOP SERVICE TO CHANGE MODE",
                        fontSize      = 7.sp,
                        color         = TextMuted.copy(0.6f),
                        letterSpacing = 1.sp,
                        fontFamily    = AppFontFamily
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── LOCAL MODELS INIT ─────────────────────────────────
            HudCollapsibleCard(
                title = "LOCAL MODELS :: INIT CHECKLIST",
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                ModelStatusRow("STT", "sherpa-onnx (zipformer)", modelsStatus.stt)
                Spacer(Modifier.height(8.dp))
                ModelStatusRow("VAD", "Silero ONNX", modelsStatus.vad)
                Spacer(Modifier.height(8.dp))
                ModelStatusRow("LLM", "LiteRTLM (Gemma)", modelsStatus.llm)
                Spacer(Modifier.height(8.dp))
                ModelStatusRow("TTS", "Android Engine", modelsStatus.tts)
                if (ttsEnginesList.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Divider(color = CardBorder, thickness = 0.5.dp)
                    Spacer(Modifier.height(12.dp))

                    Text(
                        "DETECTED TTS ENGINES",
                        fontSize = 8.sp,
                        color = NeonCyan.copy(0.8f),
                        letterSpacing = 1.5.sp,
                        fontFamily = AppFontFamily
                    )
                    Spacer(Modifier.height(8.dp))

                    ttsEnginesList.forEachIndexed { index, engine ->
                        val isSelected = engine.packageName == selectedAndroidTtsEnginePkg
                        val isSherpa = engine.packageName.contains("sherpa", ignoreCase = true) ||
                                engine.packageName.contains("k2fsa", ignoreCase = true)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (isSelected) NeonCyan.copy(0.12f)
                                    else if (isSherpa) NeonGreen.copy(0.08f)
                                    else Color.Transparent
                                )
                                .clickable {
                                    // لو الـ TTS الحالي أندرويد، خليه يختار المحرك ده
                                    if (currentTtsEngine == TtsEngineType.ANDROID_TTS) {
                                        onAndroidTtsEngineSelected(engine.packageName)
                                    }
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // مؤشر الاختيار (Radio button-like)
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .border(1.dp, if (isSelected) NeonCyan else CardBorder2, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(NeonCyan))
                                }
                            }
                            Spacer(Modifier.width(8.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    engine.label,
                                    fontSize = 10.sp,
                                    color = if (isSelected) NeonCyan else if (isSherpa) NeonGreen else TextPrimary,
                                    fontFamily = AppFontFamily
                                )
                                Text(
                                    "${engine.packageName} v${engine.version}",
                                    fontSize = 7.sp,
                                    color = TextMuted.copy(0.7f),
                                    fontFamily = AppFontFamily
                                )
                            }

                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(NeonCyan.copy(0.12f))
                                        .border(0.5.dp, NeonCyan.copy(0.4f), RoundedCornerShape(3.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("SELECTED", fontSize = 7.sp, color = NeonCyan, letterSpacing = 1.sp, fontFamily = AppFontFamily)
                                }
                            } else if (isSherpa) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(NeonGreen.copy(0.12f))
                                        .border(0.5.dp, NeonGreen.copy(0.4f), RoundedCornerShape(3.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("SHERPA", fontSize = 7.sp, color = NeonGreen, letterSpacing = 1.sp, fontFamily = AppFontFamily)
                                }
                            }
                        }

                        if (index < ttsEnginesList.size - 1) {
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(NeonGreen.copy(0.1f))
                        .border(0.5.dp, NeonGreen.copy(0.5f), RoundedCornerShape(6.dp))
                        .clickable { onInitLocalModels() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "SCAN MODELS",
                        fontSize      = 10.sp,
                        color         = if (currentMode == AxonMode.LOCAL) NeonGreen else TextMuted,
                        fontWeight    = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        fontFamily    = AppFontFamily
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── PERMISSIONS ───────────────────────────────────────
            HudCollapsibleCard(
                title = "PERMISSIONS :: ACCESS MATRIX",
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                HudPermRow(
                    label   = "MICROPHONE",
                    granted = permissionsGranted,
                    onClick = {
                        if (!permissionsGranted) {
                            val intent = Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        }
                    }
                )
                Divider(color = CardBorder, thickness = 0.5.dp,
                    modifier = Modifier.padding(vertical = 6.dp))

                val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    permissionsGranted else true
                HudPermRow(
                    label   = "NOTIFICATIONS",
                    granted = notifGranted,
                    onClick = {
                        if (!notifGranted) {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            context.startActivity(intent)
                        }
                    }
                )
                Divider(color = CardBorder, thickness = 0.5.dp,
                    modifier = Modifier.padding(vertical = 6.dp))

                HudPermRow(
                    label   = "OVERLAY",
                    granted = overlayPermGranted,
                    onClick = { if (!overlayPermGranted) onRequestOverlay() },
                    trailing = {
                        if (!overlayPermGranted) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(AccentAmber.copy(0.12f))
                                    .border(0.5.dp, AccentAmber.copy(0.5f), RoundedCornerShape(4.dp))
                                    .clickable { onRequestOverlay() }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    "GRANT",
                                    fontSize      = 8.sp,
                                    color         = AccentAmber,
                                    letterSpacing = 1.sp,
                                    fontFamily    = AppFontFamily
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(NeonGreen.copy(0.1f))
                                    .border(0.5.dp, NeonGreen.copy(0.5f), RoundedCornerShape(3.dp))
                            )
                        }
                    }
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── DIAGNOSTICS ───────────────────────────────────────
            HudCollapsibleCard(
                title = "DIAGNOSTICS :: SYSTEM SCAN",
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier          = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text          = "FULL SCAN",
                        fontSize      = 11.sp,
                        color         = TextPrimary,
                        letterSpacing = 1.sp,
                        fontFamily    = AppFontFamily,
                        modifier      = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(CardBg)
                            .border(0.5.dp, CardBorder, RoundedCornerShape(4.dp))
                            .clickable { onToggleDiagnostics() }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text          = if (showDiagnostics) "HIDE" else "SHOW",
                            fontSize      = 8.sp,
                            color         = NeonCyan,
                            letterSpacing = 1.sp,
                            fontFamily    = AppFontFamily
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(NeonGreen.copy(0.1f))
                            .border(0.5.dp, NeonGreen.copy(0.4f), RoundedCornerShape(4.dp))
                            .clickable { onRunDiagnostics() }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text          = "RUN",
                            fontSize      = 8.sp,
                            color         = NeonGreen,
                            letterSpacing = 1.sp,
                            fontFamily    = AppFontFamily
                        )
                    }
                }

                if (showDiagnostics && diagnosticResult != null) {
                    Spacer(Modifier.height(14.dp))
                    Divider(color = CardBorder, thickness = 0.5.dp)
                    Spacer(Modifier.height(14.dp))
                    HudDiagRow("ONNX MODEL",     diagnosticResult.modelFileExists)
                    Spacer(Modifier.height(10.dp))
                    HudDiagRow("PERMISSIONS",    diagnosticResult.permissionsGranted)
                    Spacer(Modifier.height(10.dp))
                    HudDiagRow("AUDIO HARDWARE", diagnosticResult.audioHardwareAvailable)

                    if (diagnosticResult.errors.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        diagnosticResult.errors.forEach { err ->
                            Row(
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "//",
                                    fontSize   = 9.sp,
                                    color      = AccentPink.copy(0.6f),
                                    fontFamily = AppFontFamily
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    err,
                                    fontSize      = 9.sp,
                                    color         = TextMuted,
                                    fontFamily    = AppFontFamily,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // ── Notification Announce Rules Card ────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(CardBg)
                    .border(0.5.dp, CardBorder, RoundedCornerShape(6.dp))
                    .clickable { onOpenNotificationRules() }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "//",
                        fontSize   = 11.sp,
                        color      = NeonCyan.copy(0.6f),
                        fontFamily = AppFontFamily
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text          = "NOTIFICATION ANNOUNCE RULES",
                        fontSize      = 11.sp,
                        color         = TextPrimary,
                        letterSpacing = 1.sp,
                        fontFamily    = AppFontFamily
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "→",
                        fontSize = 14.sp,
                        color    = TextMuted
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // ── Error Log Button ──────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(CardBg)
                    .border(0.5.dp, CardBorder, RoundedCornerShape(6.dp))
                    .clickable { onViewErrorLog() }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "//",
                        fontSize   = 11.sp,
                        color      = NeonCyan.copy(0.6f),
                        fontFamily = AppFontFamily
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text          = "VIEW ERROR LOG",
                        fontSize      = 11.sp,
                        color         = TextPrimary,
                        letterSpacing = 1.sp,
                        fontFamily    = AppFontFamily
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "→",
                        fontSize = 14.sp,
                        color    = TextMuted
                    )
                }
            }

            Spacer(Modifier.height(80.dp))
        }

        // ── Bottom Nav ────────────────────────────────────────────
        Column(modifier = Modifier.align(Alignment.BottomCenter)) {
            AxonAxonBottomNav(
                selectedTab      = 1,
                onListenClick    = onBack,  // يرجع للـ Main
                isServiceRunning = isServiceRunning,
                onSettingsClick  = {},      // احنا هنا
                onChatClick      = onOpenChat,
                onWidgetClick    = onOpenWidget,
                onPairClick      = onOpenPair  // ← جديد
            )
        }
    }
}


@Composable
private fun HudSectionLabel(text: String) {
    Text(
        text          = text,
        color         = NeonGreen.copy(0.8f),
        fontSize      = 8.sp,
        fontWeight    = FontWeight.Bold,
        letterSpacing = 2.sp,
        fontFamily    = AppFontFamily,
        modifier      = Modifier.padding(start = 20.dp, bottom = 8.dp, top = 4.dp)
    )
}

@Composable
private fun HudCollapsibleCard(
    title: String,
    defaultExpanded: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(defaultExpanded) }

    HudCard(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = NeonGreen.copy(0.8f),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                fontFamily = AppFontFamily,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (expanded) "▾" else "▸",
                fontSize = 10.sp,
                color = NeonGreen
            )
        }

        androidx.compose.animation.AnimatedVisibility(visible = expanded) {
            Column {
                Spacer(Modifier.height(8.dp))
                Divider(color = CardBorder, thickness = 0.5.dp)
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }
}

@Composable
private fun HudCard(
    modifier: Modifier = Modifier,
    content:  @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(CardBg)
            .border(0.5.dp, CardBorder, RoundedCornerShape(8.dp))
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun HudEndpointRow(
    label:    String,
    selected: Boolean,
    isActive: Boolean,
    onClick:  () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) NeonGreen.copy(0.05f) else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // HUD radio indicator
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .border(
                    1.dp,
                    if (selected) NeonGreen else CardBorder2,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(NeonGreen)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text          = label,
            color         = if (selected) TextPrimary else TextMuted,
            fontSize      = 11.sp,
            letterSpacing = 0.5.sp,
            fontFamily    = AppFontFamily,
            modifier      = Modifier.weight(1f)
        )
        if (isActive) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(NeonGreen.copy(0.12f))
                    .border(0.5.dp, NeonGreen.copy(0.4f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    "ACTIVE",
                    fontSize      = 7.sp,
                    color         = NeonGreen,
                    letterSpacing = 1.sp,
                    fontFamily    = AppFontFamily
                )
            }
        } else {
            trailing?.invoke()
        }
    }
}

@Composable
private fun HudPermRow(
    label:    String,
    granted:  Boolean,
    onClick:  (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text          = label,
                color         = TextPrimary,
                fontSize      = 11.sp,
                letterSpacing = 1.sp,
                fontFamily    = AppFontFamily
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text          = if (granted) "// ACCESS GRANTED" else "// TAP TO GRANT",
                color         = if (granted) NeonGreen.copy(0.8f) else AccentPink.copy(0.8f),
                fontSize      = 8.sp,
                letterSpacing = 0.5.sp,
                fontFamily    = AppFontFamily
            )
        }
        if (trailing != null) {
            trailing()
        } else {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (granted) NeonGreen.copy(0.1f)
                        else Color.Transparent
                    )
                    .border(
                        0.5.dp,
                        if (granted) NeonGreen.copy(0.5f) else CardBorder2,
                        RoundedCornerShape(3.dp)
                    )
            )
        }
    }
}

@Composable
private fun HudDiagRow(label: String, passed: Boolean) {
    Row(
        modifier          = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text   = if (passed) "✓" else "✗",
            fontSize = 12.sp,
            color  = if (passed) NeonGreen else AccentPink,
            fontFamily = AppFontFamily
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text          = label,
            color         = TextPrimary,
            fontSize      = 11.sp,
            letterSpacing = 1.sp,
            fontFamily    = AppFontFamily,
            modifier      = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .background(
                    if (passed) NeonGreen.copy(0.1f)
                    else AccentPink.copy(0.1f)
                )
                .border(
                    0.5.dp,
                    if (passed) NeonGreen.copy(0.4f) else AccentPink.copy(0.4f),
                    RoundedCornerShape(3.dp)
                )
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text          = if (passed) "PASS" else "FAIL",
                color         = if (passed) NeonGreen else AccentPink,
                fontSize      = 7.sp,
                letterSpacing = 1.sp,
                fontFamily    = AppFontFamily
            )
        }
    }
}

@Composable
private fun ModelStatusRow(label: String, description: String, ready: Boolean) {
    Row(
        modifier          = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (ready) NeonGreen.copy(0.1f) else CardBg)
                .border(
                    0.5.dp,
                    if (ready) NeonGreen.copy(0.5f) else CardBorder2,
                    RoundedCornerShape(3.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (ready) "\u2713" else "\u2717",
                fontSize = 9.sp,
                color    = if (ready) NeonGreen else TextMuted,
                fontFamily = AppFontFamily
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                color         = if (ready) TextPrimary else TextMuted,
                fontSize      = 11.sp,
                letterSpacing = 1.sp,
                fontFamily    = AppFontFamily
            )
            Text(
                description,
                color         = TextMuted,
                fontSize      = 7.sp,
                letterSpacing = 0.5.sp,
                fontFamily    = AppFontFamily
            )
        }
    }
}


// ══════════════════════════════════════════════════════════════════════
//  ERROR LOG SCREEN
// ══════════════════════════════════════════════════════════════════════

@Composable
fun ErrorLogScreen(
    logContent: String,
    logPath:    String,
    onClose:    () -> Unit,
    onClear:    () -> Unit,
    onOpenDevDashboard: () -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxSize().background(BgPrimary).padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(
                "// ERROR LOG",
                fontSize      = 16.sp,
                fontWeight    = FontWeight.Bold,
                color         = TextPrimary,
                letterSpacing = 3.sp,
                fontFamily    = AppFontFamily
            )
            Row {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(NeonCyan.copy(0.1f))
                        .border(0.5.dp, NeonCyan.copy(0.4f), RoundedCornerShape(4.dp))
                        .clickable { onOpenDevDashboard() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("DEV STATS", fontSize = 9.sp, color = NeonCyan,
                        letterSpacing = 1.sp,
                        fontFamily = AppFontFamily)
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(AccentPink.copy(0.1f))
                        .border(0.5.dp, AccentPink.copy(0.4f), RoundedCornerShape(4.dp))
                        .clickable { onClear() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("CLEAR", fontSize = 9.sp, color = AccentPink,
                        letterSpacing = 1.sp,
                        fontFamily = AppFontFamily)
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(NeonCyan.copy(0.1f))
                        .border(0.5.dp, NeonCyan.copy(0.4f), RoundedCornerShape(4.dp))
                        .clickable { onClose() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("CLOSE", fontSize = 9.sp, color = NeonCyan,
                        letterSpacing = 1.sp,
                        fontFamily = AppFontFamily)
                }
            }
        }
        Text(
            "PATH: $logPath",
            fontSize      = 9.sp,
            color         = TextMuted,
            fontFamily    = AppFontFamily,
            modifier      = Modifier.padding(bottom = 8.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(CardBg)
                .border(0.5.dp, CardBorder, RoundedCornerShape(8.dp))
        ) {
            Text(
                text       = logContent.ifEmpty { "// NO ERRORS LOGGED" },
                fontSize   = 10.sp,
                color      = TextPrimary.copy(0.8f),
                fontFamily = AppFontFamily,
                modifier   = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
//  DEV DASHBOARD SCREEN - ServiceStatsTracker Viewer
// ══════════════════════════════════════════════════════════════════════

@Composable
fun DevDashboardScreen(onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgPrimary)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "// DEV DASHBOARD",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                letterSpacing = 3.sp,
                fontFamily = AppFontFamily
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(NeonCyan.copy(0.1f))
                    .border(0.5.dp, NeonCyan.copy(0.4f), RoundedCornerShape(4.dp))
                    .clickable { onClose() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("CLOSE", fontSize = 9.sp, color = NeonCyan,
                    letterSpacing = 1.sp,
                    fontFamily = AppFontFamily)
            }
        }

        // Stats Overview
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("📊 SERVICE STATS", 
                    fontSize = 14.sp, 
                    fontWeight = FontWeight.Bold, 
                    color = NeonCyan,
                    fontFamily = AppFontFamily,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                StatRow("Online", if (ServiceStatsTracker.isOnline) "✅ Yes" else "❌ No")
                StatRow("Query Count", "${ServiceStatsTracker.queryCount}")
                StatRow("Avg Response", ServiceStatsTracker.avgResponseString())
            }
        }

        // Last Prompts
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("💬 LAST PROMPTS", 
                    fontSize = 14.sp, 
                    fontWeight = FontWeight.Bold, 
                    color = AccentAmber,
                    fontFamily = AppFontFamily,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                if (ServiceStatsTracker.lastSystemPrompt.isNotEmpty()) {
                    Text("System:", 
                        fontSize = 10.sp, 
                        color = TextMuted,
                        fontFamily = AppFontFamily
                    )
                    Text(ServiceStatsTracker.lastSystemPrompt.take(200) + if (ServiceStatsTracker.lastSystemPrompt.length > 200) "..." else "",
                        fontSize = 9.sp,
                        color = TextPrimary.copy(0.8f),
                        fontFamily = AppFontFamily,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                
                if (ServiceStatsTracker.lastPromptText.isNotEmpty()) {
                    Text("User:", 
                        fontSize = 10.sp, 
                        color = TextMuted,
                        fontFamily = AppFontFamily
                    )
                    Text(ServiceStatsTracker.lastPromptText.take(200) + if (ServiceStatsTracker.lastPromptText.length > 200) "..." else "",
                        fontSize = 9.sp,
                        color = TextPrimary.copy(0.8f),
                        fontFamily = AppFontFamily
                    )
                }
            }
        }

        // Tool Usage History
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("🛠️ TOOL USAGE", 
                    fontSize = 14.sp, 
                    fontWeight = FontWeight.Bold, 
                    color = AccentPink,
                    fontFamily = AppFontFamily,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                if (ServiceStatsTracker.toolUsageHistory.isEmpty()) {
                    Text("No tools used yet",
                        fontSize = 10.sp,
                        color = TextMuted,
                        fontFamily = AppFontFamily
                    )
                } else {
                    ServiceStatsTracker.toolUsageHistory.take(5).forEach { record ->
                        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                            .format(java.util.Date(record.timestamp))
                        Text("[$time] ${record.toolName}",
                            fontSize = 10.sp,
                            color = TextPrimary,
                            fontFamily = AppFontFamily
                        )
                        Text("  Params: ${record.parameters.take(100)}",
                            fontSize = 8.sp,
                            color = TextMuted,
                            fontFamily = AppFontFamily,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }
            }
        }

        // Conversation Summaries
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("🧠 CONVERSATION SUMMARIES", 
                    fontSize = 14.sp, 
                    fontWeight = FontWeight.Bold, 
                    color = NeonGreen,
                    fontFamily = AppFontFamily,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                if (ServiceStatsTracker.conversationSummaries.isEmpty()) {
                    Text("No conversations saved yet",
                        fontSize = 10.sp,
                        color = TextMuted,
                        fontFamily = AppFontFamily
                    )
                } else {
                    ServiceStatsTracker.conversationSummaries.take(5).forEach { summary ->
                        val time = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(summary.timestamp))
                        Text("[$time] Session: ${summary.sessionId.take(8)}...",
                            fontSize = 10.sp,
                            color = TextPrimary,
                            fontFamily = AppFontFamily
                        )
                        Text("  ${summary.messageCount} messages",
                            fontSize = 8.sp,
                            color = TextMuted,
                            fontFamily = AppFontFamily
                        )
                        Text("  ${summary.summary.take(100)}${if (summary.summary.length > 100) "..." else ""}",
                            fontSize = 9.sp,
                            color = TextPrimary.copy(0.8f),
                            fontFamily = AppFontFamily,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label,
            fontSize = 11.sp,
            color = TextMuted,
            fontFamily = AppFontFamily
        )
        Text(value,
            fontSize = 11.sp,
            color = TextPrimary,
            fontWeight = FontWeight.Medium,
            fontFamily = AppFontFamily
        )
    }
}
