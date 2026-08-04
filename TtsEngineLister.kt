package com.example.app_abdelbaset

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log

class TtsEngineLister(private val context: Context) {

    private var tts: TextToSpeech? = null

    data class TtsEngineInfo(
        val label: String,
        val packageName: String,
        val version: String
    )

    fun listAllEngines(onEnginesFound: ((List<TtsEngineInfo>) -> Unit)? = null) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {

                val engines = tts?.engines
                val engineList = mutableListOf<TtsEngineInfo>()

                if (engines.isNullOrEmpty()) {
                    Log.d("TTS_ENGINES", "No TTS engines found")
                    onEnginesFound?.invoke(emptyList())
                    // ← FIX: متعملش shutdown فوراً
                    scheduleShutdown()
                    return@TextToSpeech
                }

                for (engine in engines) {
                    val version = getPackageVersion(engine.name)

                    val info = TtsEngineInfo(
                        label = engine.label?.toString() ?: "Unknown",
                        packageName = engine.name,
                        version = version
                    )
                    engineList.add(info)

                    Log.d("TTS_ENGINES", """
                        Engine Name: ${engine.label}
                        Package: ${engine.name}
                        Version: $version
                    """.trimIndent())
                }

                onEnginesFound?.invoke(engineList)
                // ← FIX: متعملش shutdown فوراً
                scheduleShutdown()
            } else {
                Log.e("TTS_ENGINES", "TTS init failed")
                onEnginesFound?.invoke(emptyList())
                scheduleShutdown()
            }
        }
    }

    // ← NEW: اعمل shutdown بعد delay عشان ميأثرش على الـ engine اللي شغال
    private fun scheduleShutdown() {
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                tts?.shutdown()
                tts = null
                Log.d("TTS_ENGINES", "TtsEngineLister shutdown completed")
            } catch (_: Exception) {}
        }, 2000) // ← استنى 2 ثانية قبل الـ shutdown
    }

    private fun getPackageVersion(packageName: String): String {
        return try {
            val pm = context.packageManager
            val info = pm.getPackageInfo(packageName, 0)
            info.versionName ?: "N/A"
        } catch (e: PackageManager.NameNotFoundException) {
            "N/A"
        } catch (e: Exception) {
            "N/A"
        }
    }
}