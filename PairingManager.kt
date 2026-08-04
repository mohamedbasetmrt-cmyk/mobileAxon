package com.example.app_abdelbaset

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.UUID
import javax.crypto.AEADBadTagException

data class PairedDesktop(
    val deviceId: String,
    val name: String,
    val tailscaleIp: String = "",
    val port: Int = 8000,
    val lastSeen: Long = 0L,
    val online: Boolean = false
)

data class PairingInfo(
    val deviceId: String,
    val deviceName: String,
    val accessToken: String,
    val refreshToken: String,
    val pairedDesktops: List<PairedDesktop>
)

class PairingManager(private val context: Context) {

    companion object {
        private const val TAG = "PairingManager"
        private const val PREFS_NAME = "axon_pairing_secure"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_PAIRED_JSON = "paired_desktops_json"
        private const val MASTER_KEY_ALIAS = "axon_pairing_master_key"
    }

    private val securePrefs: android.content.SharedPreferences by lazy {
        createSecurePrefsWithRecovery()
    }

    /**
     * بيعمل EncryptedSharedPreferences مع handling للـ corruption
     * لو فشل فتح الملف المشفر، بيمسح المفتاح ويعيد إنشاءه من جديد
     */
    private fun createSecurePrefsWithRecovery(): android.content.SharedPreferences {
        return try {
            createEncryptedPrefs()
        } catch (e: Exception) {
            when (e) {
                is AEADBadTagException,
                is javax.crypto.BadPaddingException,
                is java.security.InvalidKeyException,
                is java.security.KeyStoreException -> {
                    Log.w(TAG, "Encrypted prefs corrupted (likely key mismatch), resetting: ${e.javaClass.simpleName}: ${e.message}")
                    resetKeyAndPrefs()
                    createEncryptedPrefs()
                }
                else -> {
                    Log.e(TAG, "Unexpected error creating encrypted prefs: ${e.message}", e)
                    // Fallback: use regular SharedPreferences (non-encrypted) as last resort
                    context.getSharedPreferences("${PREFS_NAME}_fallback", Context.MODE_PRIVATE)
                }
            }
        }
    }

    private fun createEncryptedPrefs(): android.content.SharedPreferences {
        val masterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * بيمسح الـ Master Key من Android Keystore والـ prefs file
     */
    private fun resetKeyAndPrefs() {
        try {
            // 1. امسح الملف المشفر
            context.deleteSharedPreferences(PREFS_NAME)
            Log.d(TAG, "Deleted corrupted prefs file: $PREFS_NAME")
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete prefs file: ${e.message}")
        }

        try {
            // 2. امسح المفتاح من Keystore
            val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MASTER_KEY_ALIAS)
                Log.d(TAG, "Deleted corrupted master key: $MASTER_KEY_ALIAS")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete master key: ${e.message}")
        }
    }

    fun getDeviceId(): String {
        var id = securePrefs.getString(KEY_DEVICE_ID, null)
        if (id == null) {
            id = "phone_${UUID.randomUUID().toString().take(12)}"
            securePrefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    fun getDeviceName(): String {
        var name = securePrefs.getString(KEY_DEVICE_NAME, null)
        if (name == null) {
            name = getFriendlyDeviceName()
            securePrefs.edit().putString(KEY_DEVICE_NAME, name).apply()
        }
        return name
    }

    fun setDeviceName(name: String) {
        securePrefs.edit().putString(KEY_DEVICE_NAME, name).apply()
    }

    fun getAccessToken(): String? = securePrefs.getString(KEY_ACCESS_TOKEN, null)

    fun getRefreshToken(): String? = securePrefs.getString(KEY_REFRESH_TOKEN, null)

    fun saveTokens(access: String, refresh: String) {
        securePrefs.edit()
            .putString(KEY_ACCESS_TOKEN, access)
            .putString(KEY_REFRESH_TOKEN, refresh)
            .apply()
    }

    fun getPairedDesktops(): List<PairedDesktop> {
        val json = securePrefs.getString(KEY_PAIRED_JSON, null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                PairedDesktop(
                    deviceId = obj.getString("device_id"),
                    name = obj.getString("name"),
                    tailscaleIp = obj.optString("tailscale_ip", ""),
                    port = obj.optInt("port", 8000),
                    lastSeen = obj.optLong("last_seen", 0L),
                    online = obj.optBoolean("online", false)
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    fun isPaired(): Boolean {
        return getAccessToken() != null && getPairedDesktops().isNotEmpty()
    }

    fun getActiveEndpoint(endpoint: String): String {
        val ep = if (endpoint == "Custom\u2026") {
            context.getSharedPreferences("axon_prefs", Context.MODE_PRIVATE)
                .getString("custom_endpoint", "") ?: MainActivity.PRESET_ENDPOINTS[0]
        } else endpoint
        return "https://$ep"
    }

    fun getWsEndpoint(endpoint: String): String {
        val ep = if (endpoint == "Custom\u2026") {
            context.getSharedPreferences("axon_prefs", Context.MODE_PRIVATE)
                .getString("custom_endpoint", "") ?: MainActivity.PRESET_ENDPOINTS[0]
        } else endpoint
        return "wss://$ep"
    }

    fun verifyPairing(
        baseUrl: String,
        pairId: String,
        deviceName: String,
        onSuccess: (token: String, refreshToken: String, desktopId: String, desktopName: String) -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            try {
                val deviceId = getDeviceId()
                val url = URL("$baseUrl/api/pairing/verify")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")

                val body = JSONObject().apply {
                    put("pair_id", pairId)
                    put("device_id", deviceId)
                    put("device_name", deviceName)
                }
                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

                val code = conn.responseCode
                if (code in 200..299) {
                    val resp = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                    val json = JSONObject(resp)
                    val accessToken = json.getString("access_token")
                    val refreshToken = json.getString("refresh_token")
                    val desktopId = json.getString("desktop_id")
                    val desktopName = json.getString("desktop_name")

                    securePrefs.edit()
                        .putString(KEY_ACCESS_TOKEN, accessToken)
                        .putString(KEY_REFRESH_TOKEN, refreshToken)
                        .apply()

                    addPairedDesktop(PairedDesktop(deviceId = desktopId, name = desktopName, online = true, lastSeen = System.currentTimeMillis()))

                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onSuccess(accessToken, refreshToken, desktopId, desktopName)
                    }
                } else {
                    val err = BufferedReader(InputStreamReader(conn.errorStream)).use { it.readText() }
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onError("Server error $code: $err")
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onError(e.message ?: "Connection error")
                }
            }
        }.apply { name = "PairingVerify"; isDaemon = true; start() }
    }

    fun addPairedDesktop(desktop: PairedDesktop) {
        val list = getPairedDesktops().toMutableList()
        val existing = list.indexOfFirst { it.deviceId == desktop.deviceId }
        if (existing >= 0) {
            list[existing] = desktop
        } else {
            list.add(desktop)
        }
        savePairedDesktops(list)
    }

    fun removePairedDesktop(deviceId: String) {
        val list = getPairedDesktops().filter { it.deviceId != deviceId }
        savePairedDesktops(list)
        if (list.isEmpty()) {
            securePrefs.edit()
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_REFRESH_TOKEN)
                .apply()
        }
    }

    private fun savePairedDesktops(list: List<PairedDesktop>) {
        val arr = org.json.JSONArray()
        list.forEach { d ->
            arr.put(JSONObject().apply {
                put("device_id", d.deviceId)
                put("name", d.name)
                put("tailscale_ip", d.tailscaleIp)
                put("port", d.port)
                put("last_seen", d.lastSeen)
                put("online", d.online)
            })
        }
        securePrefs.edit().putString(KEY_PAIRED_JSON, arr.toString()).apply()
    }

    fun setDesktopOnline(deviceId: String, online: Boolean) {
        val list = getPairedDesktops().map {
            if (it.deviceId == deviceId) it.copy(online = online, lastSeen = System.currentTimeMillis())
            else it
        }
        savePairedDesktops(list)
    }

    fun markAllDesktopsOffline() {
        val list = getPairedDesktops().map { it.copy(online = false) }
        savePairedDesktops(list)
    }

    fun getTailscaleIP(): String? {
        return NetworkInterface.getNetworkInterfaces()?.asSequence().orEmpty()
            .filter { it.isUp && (it.name.contains("tailscale", true) || it.name.contains("tun", true)) }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.startsWith("100.") == true }
            ?.hostAddress
    }

    fun clearAll() {
        securePrefs.edit().clear().apply()
    }

    private fun getFriendlyDeviceName(): String {
        val model = Build.MODEL
        val manufacturer = Build.MANUFACTURER
        return "$manufacturer $model"
    }
}