package com.example.app_abdelbaset

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class PairingForegroundService : Service() {

    private var pairingServiceManager: PairingServiceManager? = null

    companion object {
        private const val TAG        = "PairingFgService"
        private const val NOTIF_ID   = 1002
        private const val CHANNEL_ID = "pairing_service_channel"
        const val EXTRA_ENDPOINT      = "endpoint"

        @Volatile
        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Pairing active"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")

        // Already initialized — keep existing connection
        if (pairingServiceManager != null) {
            return START_STICKY
        }

        try {
            val prefs = getSharedPreferences("axon_prefs", Context.MODE_PRIVATE)

            // Read from intent or from prefs (for START_STICKY restarts)
            var endpoint = intent?.getStringExtra(EXTRA_ENDPOINT)
                ?: prefs.getString("endpoint", null)

            // Resolve "Custom…" placeholder to the actual custom endpoint
            if (endpoint == "Custom\u2026") {
                endpoint = prefs.getString("custom_endpoint", null)
            }

            if (endpoint.isNullOrBlank()) {
                Log.w(TAG, "No endpoint, stopping")
                stopSelf()
                return START_NOT_STICKY
            }

            val pairingManager = PairingManager(applicationContext)
            if (!pairingManager.isPaired()) {
                Log.d(TAG, "No paired devices, stopping")
                stopSelf()
                return START_NOT_STICKY
            }

            // قراءة Tailscale IP من الـ Intent (جديد من PairDesktopScreen)
            val tailscaleIp = intent?.getStringExtra("tailscale_ip")
            val port = intent?.getIntExtra("port", 8000) ?: 8000

            pairingServiceManager = PairingServiceManager(applicationContext)
            pairingServiceManager?.initialize(endpoint, tailscaleIp, port)
            return START_STICKY
        } catch (e: Throwable) {
            Log.e(TAG, "FATAL in onStartCommand", e)
            stopSelf()
            return START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        stopForeground(STOP_FOREGROUND_REMOVE)
        pairingServiceManager?.release()
        pairingServiceManager = null
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification {
        val mainPending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Axon Pairing")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(mainPending)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Pairing Service", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the pairing WebSocket connection alive"
                setShowBadge(false)
            }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
        }
    }
}
