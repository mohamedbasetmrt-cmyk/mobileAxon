package com.example.app_abdelbaset

import android.content.Context
import android.media.AudioRecord
import android.util.Log
import java.io.File

object DiagnosticHelper {

    private const val TAG = "DIAGNOSTIC"

    fun runAllChecks(context: Context): DiagnosticResult {
        Log.d(TAG, "================================================")
        Log.d(TAG, "بدء الفحص التشخيصي الشامل")
        Log.d(TAG, "================================================")

        val result = DiagnosticResult()

        checkModelFile(context, result)
        checkPermissions(context, result)
        checkAudioHardware(result)

        Log.d(TAG, "================================================")
        Log.d(TAG, "نتيجة الفحص: ${if (result.allPassed) "نجح" else "فشل"}")
        Log.d(TAG, "================================================")

        return result
    }

    private fun checkModelFile(context: Context, result: DiagnosticResult) {
        Log.d(TAG, "\nفحص ملف ONNX model...")

        try {
            val models = context.assets.list("") ?: emptyArray()
            val onnxModel = models.firstOrNull { it.endsWith(".onnx") }

            if (onnxModel != null) {
                result.modelFileExists = true
                result.modelFileName = onnxModel

                val fileSize = context.assets.open(onnxModel).use { it.available() }
                result.modelFileSize = fileSize

                Log.d(TAG, "وجد الملف: $onnxModel ($fileSize bytes)")

                if (fileSize < 1024) {
                    result.errors.add("Model file is too small (${fileSize} bytes)")
                }

                try {
                    val testFile = File(context.filesDir, "test_$onnxModel")
                    context.assets.open(onnxModel).use { input ->
                        testFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (testFile.exists() && testFile.length() == fileSize.toLong()) {
                        Log.d(TAG, "تم نسخ الملف بنجاح للاختبار")
                        testFile.delete()
                    } else {
                        result.errors.add("Failed to copy model file")
                    }
                } catch (e: Exception) {
                    result.errors.add("Error reading model file: ${e.message}")
                }

            } else {
                result.modelFileExists = false
                result.errors.add("No .onnx model file found in assets")
                Log.e(TAG, "ضع الملف في: app/src/main/assets/ak_son.onnx")
            }

        } catch (e: Exception) {
            result.modelFileExists = false
            result.errors.add("Error checking assets: ${e.message}")
        }
    }

    private fun checkPermissions(context: Context, result: DiagnosticResult) {
        Log.d(TAG, "\nفحص الأذونات...")

        val recordAudio = context.checkSelfPermission(
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        result.permissionsGranted = recordAudio

        if (recordAudio) {
            Log.d(TAG, "إذن RECORD_AUDIO ممنوح")
        } else {
            result.errors.add("RECORD_AUDIO permission not granted")
            Log.e(TAG, "إذن RECORD_AUDIO غير ممنوح!")
        }
    }

    private fun checkAudioHardware(result: DiagnosticResult) {
        Log.d(TAG, "\nفحص الميكروفون...")

        try {
            val minBuf = AudioRecord.getMinBufferSize(16000,
                android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT)

            result.audioHardwareAvailable = minBuf > 0

            if (minBuf > 0) {
                Log.d(TAG, "الميكروفون يعمل (buffer size=$minBuf)")
            } else {
                result.errors.add("Microphone not available")
                Log.e(TAG, "الميكروفون غير متاح!")
            }
        } catch (e: Exception) {
            result.audioHardwareAvailable = false
            result.errors.add("Error checking microphone: ${e.message}")
        }
    }

    fun printSummary(result: DiagnosticResult) {
        Log.d(TAG, "\n")
        Log.d(TAG, "═══════════════════════════════════════════════")
        Log.d(TAG, "ملخص الفحص التشخيصي")
        Log.d(TAG, "═══════════════════════════════════════════════")
        Log.d(TAG, "")
        Log.d(TAG, "ONNX Model: ${if (result.modelFileExists) "موجود" else "غير موجود"}")
        if (result.modelFileName != null) {
            Log.d(TAG, "  - الاسم: ${result.modelFileName}")
            Log.d(TAG, "  - الحجم: ${result.modelFileSize} bytes")
        }
        Log.d(TAG, "الأذونات: ${if (result.permissionsGranted) "ممنوحة" else "غير ممنوحة"}")
        Log.d(TAG, "الميكروفون: ${if (result.audioHardwareAvailable) "متاح" else "غير متاح"}")
        Log.d(TAG, "")

        if (result.errors.isNotEmpty()) {
            Log.d(TAG, "الأخطاء:")
            result.errors.forEach { error -> Log.d(TAG, "   $error") }
            Log.d(TAG, "")
            Log.d(TAG, "يرجى حل المشاكل قبل تشغيل الخدمة")
        } else {
            Log.d(TAG, "كل الفحوصات نجحت!")
            Log.d(TAG, "يمكنك الآن تشغيل الخدمة")
        }

        Log.d(TAG, "═══════════════════════════════════════════════")
        Log.d(TAG, "")
    }
}

data class DiagnosticResult(
    var modelFileExists: Boolean = false,
    var modelFileName: String? = null,
    var modelFileSize: Int = 0,
    var permissionsGranted: Boolean = false,
    var audioHardwareAvailable: Boolean = false,
    var errors: MutableList<String> = mutableListOf()
) {
    val allPassed: Boolean
        get() = modelFileExists &&
                permissionsGranted &&
                audioHardwareAvailable &&
                errors.isEmpty()
}
