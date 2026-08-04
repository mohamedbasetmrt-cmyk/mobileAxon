package com.example.app_abdelbaset

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * 📝 ErrorLogger - يحفظ كل الأخطاء في ملف على الجهاز
 * الملف يتحفظ في: /storage/emulated/0/Download/voice_assistant_errors.log
 */
object ErrorLogger {

    private const val TAG = "ErrorLogger"
    private const val LOG_FILENAME = "voice_assistant_errors.log"

    // مسار الملف في مجلد Download
    private fun getLogFile(context: Context): File {
        return File(context.filesDir, LOG_FILENAME)
    }

    /**
     * كتابة رسالة خطأ في الملف
     */
    fun logError(context: Context, tag: String, message: String, exception: Throwable? = null) {
        try {
            val logFile = getLogFile(context)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

            val logMessage = buildString {
                appendLine("=" .repeat(80))
                appendLine("[$timestamp] ERROR in $tag")
                appendLine("=" .repeat(80))
                appendLine("Message: $message")

                if (exception != null) {
                    appendLine("\nException: ${exception::class.java.simpleName}")
                    appendLine("Exception Message: ${exception.message}")
                    appendLine("\nStack Trace:")
                    appendLine(exception.stackTraceToString())
                }

                appendLine("\n" + "=" .repeat(80))
                appendLine()
            }

            // كتابة في الملف
            FileOutputStream(logFile, true).use { output ->
                output.write(logMessage.toByteArray())
            }

            // كتابة في Logcat أيضاً
            Log.e(TAG, "Error logged to: ${logFile.absolutePath}")
            Log.e(tag, message, exception)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to write error log", e)
        }
    }

    /**
     * كتابة رسالة عادية (Info)
     */
    fun logInfo(context: Context, tag: String, message: String) {
        try {
            val logFile = getLogFile(context)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

            val logMessage = "[$timestamp] INFO [$tag]: $message\n"

            FileOutputStream(logFile, true).use { output ->
                output.write(logMessage.toByteArray())
            }

            Log.i(tag, message)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to write info log", e)
        }
    }

    /**
     * مسح الملف القديم
     */
    fun clearLog(context: Context): Boolean {
        return try {
            val logFile = getLogFile(context)
            if (logFile.exists()) {
                logFile.delete()
                Log.d(TAG, "Log file cleared")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear log", e)
            false
        }
    }

    /**
     * قراءة محتوى الملف
     */
    fun readLog(context: Context): String {
        return try {
            val logFile = getLogFile(context)
            if (logFile.exists()) {
                logFile.readText()
            } else {
                "No log file found"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read log", e)
            "Error reading log: ${e.message}"
        }
    }

    /**
     * الحصول على مسار الملف
     */
    fun getLogFilePath(context: Context): String {
        return getLogFile(context).absolutePath
    }

    /**
     * كتابة معلومات النظام
     */
    fun logSystemInfo(context: Context) {
        try {
            val logFile = getLogFile(context)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

            val systemInfo = buildString {
                appendLine("\n" + "=" .repeat(80))
                appendLine("SYSTEM INFO - $timestamp")
                appendLine("=" .repeat(80))
                appendLine("Android Version: ${android.os.Build.VERSION.RELEASE}")
                appendLine("SDK Level: ${android.os.Build.VERSION.SDK_INT}")
                appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("App Version: ${try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch(e: Exception) { "Unknown" }}")
                appendLine("=" .repeat(80))
                appendLine()
            }

            FileOutputStream(logFile, true).use { output ->
                output.write(systemInfo.toByteArray())
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to log system info", e)
        }
    }
}