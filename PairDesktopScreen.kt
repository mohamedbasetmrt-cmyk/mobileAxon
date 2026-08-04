package com.example.app_abdelbaset

import android.Manifest
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.app_abdelbaset.ui.theme.*
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import android.content.Intent
import java.net.URL
import java.net.HttpURLConnection
import java.io.OutputStreamWriter
import java.io.BufferedReader
import java.io.InputStreamReader

enum class PairingStatus {
    IDLE, SCANNING, VERIFYING, PAIRED, ERROR
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairDesktopScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenChat: () -> Unit = {},
    onOpenWidget: () -> Unit = {},
    endpoint: String,
    pairingManager: PairingManager,
    onPaired: () -> Unit,
    onUnpaired: () -> Unit = {}
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(PairingStatus.IDLE) }
    var errorMessage by remember { mutableStateOf("") }
    var pairedDesktops by remember { mutableStateOf(pairingManager.getPairedDesktops()) }
    var deviceDisplayName by remember { mutableStateOf(pairingManager.getDeviceName()) }
    var showNameInput by remember { mutableStateOf(false) }
    var nameInputText by remember { mutableStateOf(deviceDisplayName) }
    var scanResult by remember { mutableStateOf<String?>(null) }
    var testLoading by remember { mutableStateOf<String?>(null) }
    var testResults by remember { mutableStateOf(mapOf<String, Boolean?>()) }

    fun testDevice(deviceId: String, deviceName: String) {
        if (testLoading != null) return
        testLoading = deviceId

        // نجيب الـ IP من الـ paired desktop المخزن
        val paired = pairingManager.getPairedDesktops().firstOrNull { it.deviceId == deviceId }
        val baseUrl = if (paired?.tailscaleIp?.isNotEmpty() == true) {
            "http://${paired.tailscaleIp}:${paired.port}"
        } else {
            pairingManager.getActiveEndpoint(endpoint)
        }

        Thread {
            try {
                val url = java.net.URL("$baseUrl/api/pairing/devices/$deviceId")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val code = conn.responseCode
                if (code in 200..299) {
                    val resp = java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream)).use { it.readText() }
                    val json = org.json.JSONObject(resp)
                    val devices = json.optJSONArray("devices")
                    val online = devices != null && devices.length() > 0 && devices.getJSONObject(0).optBoolean("online", false)
                    conn.disconnect()
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        testResults = testResults + (deviceId to online)
                        testLoading = null
                    }
                } else {
                    conn.disconnect()
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        testResults = testResults + (deviceId to null)
                        testLoading = null
                    }
                }
            } catch (e: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    testResults = testResults + (deviceId to null)
                    testLoading = null
                }
            }
        }.apply { name = "PairTest-$deviceName"; isDaemon = true; start() }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            status = PairingStatus.SCANNING
        } else {
            Toast.makeText(context, "Camera permission required for QR scanning", Toast.LENGTH_LONG).show()
        }
    }

    fun handleQrResult(qrText: String) {
        if (status == PairingStatus.VERIFYING || status == PairingStatus.PAIRED) return
        try {
            val json = org.json.JSONObject(qrText)
            val tailscaleIp = json.optString("tailscale_ip", "")
            val port = json.optInt("port", 8000)
            val pairingCode = json.optString("pairing_code", "")
            val desktopName = json.optString("device_name", "Desktop")

            if (tailscaleIp.isBlank() || pairingCode.isBlank()) {
                errorMessage = "Invalid QR code: missing tailscale_ip or pairing_code"
                status = PairingStatus.ERROR
                return
            }

            status = PairingStatus.VERIFYING
            val baseUrl = "http://$tailscaleIp:$port"
            val phoneId = pairingManager.getDeviceId()
            val phoneName = pairingManager.getDeviceName()
            val phoneIp = pairingManager.getTailscaleIP() ?: ""

            // POST مباشر على اللابتوب
            Thread {
                try {
                    val url = URL("$baseUrl/api/pairing/pair")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")

                    val body = org.json.JSONObject().apply {
                        put("phone_name", phoneName)
                        put("phone_id", phoneId)
                        put("phone_ip", phoneIp)
                        put("pairing_code", pairingCode)
                    }
                    OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

                    val code = conn.responseCode
                    if (code in 200..299) {
                        val resp = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                        val respJson = org.json.JSONObject(resp)
                        val desktopId = respJson.getString("desktop_id")
                        val desktopNameResp = respJson.optString("desktop_name", desktopName)
                        val desktopIp = respJson.optString("desktop_ip", tailscaleIp)
                        val desktopPort = respJson.optInt("port", port)
                        val accessToken = respJson.optString("access_token", "")
                        val refreshToken = respJson.optString("refresh_token", "")

                        // حفظ الـ tokens (مطلوب عشان isPaired())
                        if (accessToken.isNotEmpty()) {
                            pairingManager.saveTokens(accessToken, refreshToken)
                        }

                        pairingManager.addPairedDesktop(PairedDesktop(
                            deviceId = desktopId,
                            name = desktopNameResp,
                            tailscaleIp = desktopIp,
                            port = desktopPort,
                            online = true,
                            lastSeen = System.currentTimeMillis()
                        ))

                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            status = PairingStatus.PAIRED
                            pairedDesktops = pairingManager.getPairedDesktops()
                            Toast.makeText(context, "Paired with $desktopNameResp", Toast.LENGTH_SHORT).show()

                            val serviceIntent = Intent(context, PairingForegroundService::class.java).apply {
                                putExtra(PairingForegroundService.EXTRA_ENDPOINT, endpoint)
                                putExtra("tailscale_ip", desktopIp)
                                putExtra("port", desktopPort)
                            }
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                ContextCompat.startForegroundService(context, serviceIntent)
                            }, 500)
                            onPaired()
                        }
                    } else {
                        val err = BufferedReader(InputStreamReader(conn.errorStream)).use { it.readText() }
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            errorMessage = "Pairing failed: ${conn.responseCode} - $err"
                            status = PairingStatus.ERROR
                        }
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        errorMessage = "Connection error: ${e.message}"
                        status = PairingStatus.ERROR
                    }
                }
            }.apply { name = "DirectPair"; isDaemon = true; start() }

        } catch (e: Exception) {
            errorMessage = "Invalid QR: ${e.message}"
            status = PairingStatus.ERROR
        }
    }

    fun removeDesktop(deviceId: String) {
        pairingManager.removePairedDesktop(deviceId)
        pairedDesktops = pairingManager.getPairedDesktops()
        if (pairedDesktops.isEmpty()) onUnpaired()
        Toast.makeText(context, "Desktop removed", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(scanResult) {
        scanResult?.let {
            handleQrResult(it)
            scanResult = null
        }
    }

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
                        "PAIR DESKTOP",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        letterSpacing = 4.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "QR PAIRING",
                        fontSize = 8.sp,
                        color = TextMuted,
                        letterSpacing = 2.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // ── Device Name ──────────────────────────────────────────
            HudSectionLabel("DEVICE :: NAME")
            HudCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showNameInput) {
                        OutlinedTextField(
                            value = nameInputText,
                            onValueChange = { nameInputText = it },
                            modifier = Modifier.weight(1f).height(40.dp),
                            textStyle = LocalTextStyle.current.copy(
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextPrimary
                            ),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonGreen,
                                unfocusedBorderColor = CardBorder,
                                cursorColor = NeonGreen
                            )
                        )
                        Spacer(Modifier.width(6.dp))
                        Button(
                            onClick = {
                                val trimmed = nameInputText.trim()
                                if (trimmed.isNotEmpty()) {
                                    deviceDisplayName = trimmed
                                    pairingManager.setDeviceName(trimmed)
                                }
                                showNameInput = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonGreen.copy(alpha = 0.15f)),
                            modifier = Modifier.height(40.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Save", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = NeonGreen, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Text(
                            "DEVICE",
                            fontSize = 9.sp,
                            color = TextMuted,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.width(12.dp))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(4.dp))
                                .background(CardBg2)
                                .border(0.5.dp, CardBorder, RoundedCornerShape(4.dp))
                                .clickable { showNameInput = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                deviceDisplayName,
                                fontSize = 12.sp,
                                color = NeonGreen,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── QR Scanner ──────────────────────────────────────────
            HudSectionLabel("QR :: SCANNER")

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                when (status) {
                    PairingStatus.IDLE -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(240.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(CardBg)
                                    .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
                                    .clickable { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(NeonGreen.copy(alpha = 0.08f))
                                            .border(0.5.dp, NeonGreen.copy(alpha = 0.2f), RoundedCornerShape(12.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.CameraAlt,
                                            contentDescription = null,
                                            tint = NeonGreen,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        "SCAN QR CODE",
                                        fontSize = 10.sp,
                                        color = TextPrimary,
                                        fontFamily = FontFamily.Monospace,
                                        letterSpacing = 2.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "tap to start camera",
                                        fontSize = 8.sp,
                                        color = TextMuted,
                                        fontFamily = FontFamily.Monospace,
                                        letterSpacing = 1.sp
                                    )
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                                colors = ButtonDefaults.buttonColors(containerColor = NeonGreen.copy(alpha = 0.12f)),
                                modifier = Modifier.height(36.dp),
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    "START SCANNER",
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = NeonGreen,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }

                    PairingStatus.SCANNING -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(280.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .border(1.dp, NeonGreen.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                            ) {
                                QRScannerView(
                                    modifier = Modifier.fillMaxSize(),
                                    onQrScanned = { text ->
                                        scanResult = text
                                    }
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "POINT AT QR CODE ON AXON DESKTOP",
                                fontSize = 9.sp,
                                color = TextMuted,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(NeonGreen)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "SCANNING",
                                    fontSize = 9.sp,
                                    color = NeonGreen,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 2.sp
                                )
                            }
                        }
                    }

                    PairingStatus.VERIFYING -> {
                        Box(
                            modifier = Modifier
                                .size(240.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(CardBg)
                                .border(1.dp, CardBorder, RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    color = NeonGreen,
                                    modifier = Modifier.size(40.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    "VERIFYING",
                                    fontSize = 10.sp,
                                    color = TextMuted,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 2.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    PairingStatus.PAIRED -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(240.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(CardBg)
                                    .border(1.dp, NeonGreen.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(CircleShape)
                                            .background(NeonGreen.copy(alpha = 0.12f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "✓",
                                            fontSize = 32.sp,
                                            color = NeonGreen,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        "DEVICE PAIRED",
                                        fontSize = 12.sp,
                                        color = NeonGreen,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 2.sp
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { status = PairingStatus.IDLE },
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(0.5.dp, CardBorder),
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    "PAIR ANOTHER",
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextMuted,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }

                    PairingStatus.ERROR -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(240.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(CardBg)
                                    .border(1.dp, AccentPink.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(CircleShape)
                                            .background(AccentPink.copy(alpha = 0.12f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "!",
                                            fontSize = 32.sp,
                                            color = AccentPink,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        errorMessage,
                                        fontSize = 9.sp,
                                        color = AccentPink,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    status = PairingStatus.IDLE
                                    errorMessage = ""
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentPink.copy(alpha = 0.12f)),
                                modifier = Modifier.height(36.dp),
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    "TRY AGAIN",
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = AccentPink,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            // ── Paired Desktops ──────────────────────────────────────
            HudSectionLabel("CONNECTED :: DESKTOPS")
            HudCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                if (pairedDesktops.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "NO DESKTOPS PAIRED YET",
                            fontSize = 10.sp,
                            color = TextMuted.copy(alpha = 0.5f),
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                    }
                } else {
                    pairedDesktops.forEachIndexed { index, desktop ->
                        PairedDesktopRow(
                            desktop = desktop,
                            testResult = testResults[desktop.deviceId],
                            testLoading = testLoading == desktop.deviceId,
                            onTest = { testDevice(desktop.deviceId, desktop.name) },
                            onUnpair = { removeDesktop(desktop.deviceId) }
                        )
                        if (index < pairedDesktops.lastIndex) {
                            Divider(color = CardBorder, thickness = 0.5.dp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(100.dp))
        }

        // ── Bottom Nav ───────────────────────────────────────────
        AxonAxonBottomNav(
            selectedTab = 5,
            isServiceRunning = false,
            onListenClick = onBack,
            onSettingsClick = onOpenSettings,
            onChatClick = onOpenChat,
            onWidgetClick = onOpenWidget,
            onPairClick = {},
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

// ── Paired Desktop Row ───────────────────────────────────────────────────

@Composable
private fun PairedDesktopRow(
    desktop: PairedDesktop,
    testResult: Boolean?,
    testLoading: Boolean,
    onTest: () -> Unit,
    onUnpair: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(NeonCyan.copy(alpha = 0.08f))
                .border(0.5.dp, NeonCyan.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("\uD83D\uDCBB", fontSize = 16.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                desktop.name,
                fontSize = 13.sp,
                color = TextPrimary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (desktop.online) NeonGreen else TextMuted)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (desktop.online) "ONLINE" else "OFFLINE",
                    fontSize = 7.sp,
                    color = TextMuted,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                if (testResult != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (testResult) "✓" else "✗",
                        fontSize = 7.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (testResult) NeonGreen else AccentPink
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        if (testResult) "ONLINE" else "OFFLINE",
                        fontSize = 7.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (testResult) NeonGreen else AccentPink,
                        letterSpacing = 1.sp
                    )
                } else if (testLoading) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "TESTING",
                        fontSize = 7.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextMuted,
                        letterSpacing = 1.sp
                    )
                }
            }
        }

        Button(
            onClick = onTest,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (testLoading) TextMuted.copy(alpha = 0.08f) else NeonCyan.copy(alpha = 0.1f)
            ),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            modifier = Modifier.height(24.dp),
            enabled = !testLoading,
            shape = RoundedCornerShape(4.dp)
        ) {
            Text(
                "TEST",
                fontSize = 7.sp,
                fontFamily = FontFamily.Monospace,
                color = if (testLoading) TextMuted else NeonCyan,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(4.dp))
        Button(
            onClick = onUnpair,
            colors = ButtonDefaults.buttonColors(containerColor = AccentPink.copy(alpha = 0.1f)),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            modifier = Modifier.height(24.dp),
            shape = RoundedCornerShape(4.dp)
        ) {
            Text(
                "UNPAIR",
                fontSize = 7.sp,
                fontFamily = FontFamily.Monospace,
                color = AccentPink,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ── QR Scanner View ──────────────────────────────────────────────────────

@Composable
fun QRScannerView(
    modifier: Modifier = Modifier,
    onQrScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy: ImageProxy ->
                    processQrImage(imageProxy) { qrText ->
                        qrText?.let {
                            onQrScanned(it)
                        }
                    }
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    Log.e("QRScanner", "Camera bind error", e)
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        }
    )
}

private fun processQrImage(
    imageProxy: ImageProxy,
    onResult: (String?) -> Unit
) {
    @androidx.camera.core.ExperimentalGetImage
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        val scanner = BarcodeScanning.getClient()

        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    if (barcode.valueType == Barcode.TYPE_TEXT) {
                        val qrText = barcode.rawValue
                        if (qrText != null) {
                            onResult(qrText)
                        }
                    }
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    } else {
        imageProxy.close()
    }
}

// ── HUD Components ───────────────────────────────────────────────────────

@Composable
private fun HudSectionLabel(text: String) {
    Text(
        text = text,
        color = NeonGreen.copy(0.8f),
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(start = 20.dp, bottom = 8.dp, top = 4.dp)
    )
}

@Composable
private fun HudCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
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
