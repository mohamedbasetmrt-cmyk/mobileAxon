package com.example.app_abdelbaset

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.*
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class HologramOverlayService : Service() {

    companion object {
        private const val TAG = "HologramOrb"
        private const val NOTIF_ID   = 2001
        private const val CHANNEL_ID = "hologram_overlay_channel"
        private const val ORB_SIZE_DP      = 50
        private const val EXPANDED_WIDTH_DP = 250
        private const val CORNER_MARGIN_DP = 16  // ← Increased margin
        private const val POLL_MS = 64L
    }

    private var windowManager: WindowManager? = null
    private var orbView: SimpleOrbView? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private var orbSizePx = 0
    private var expandedWidthPx = 0

    private val orbTextReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.example.app_abdelbaset.ORB_TEXT" -> {
                    val text = intent.getStringExtra("text") ?: ""
                    Log.d(TAG, "Orb text received: $text")
                    if (text.isEmpty()) {
                        orbView?.clearText()
                    } else {
                        orbView?.setText(text)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            val density = resources.displayMetrics.density
            orbSizePx = (ORB_SIZE_DP * density).toInt()
            expandedWidthPx = (EXPANDED_WIDTH_DP * density).toInt()
            val marginPx  = (CORNER_MARGIN_DP * density).toInt()

            // ← FIX: Use Gravity.END (not RIGHT) for proper RTL/LTR support
            // The view is positioned at BOTTOM | END (right corner)
            params = WindowManager.LayoutParams(
                orbSizePx,   // Full expanded width
                orbSizePx,         // Fixed height 50dp
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or  // ← Allow drawing outside bounds
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT  // ← TRANSLUCENT not RGBA_8888 for proper transparency
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END  // ← BOTTOM | END (right corner)
                x = marginPx   // Margin from right edge
                y = marginPx   // Margin from bottom edge
            }

            orbView = SimpleOrbView(this).apply {
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }

            windowManager?.addView(orbView, params)
            Log.i(TAG, "OrbView added at BOTTOM|END (50x250dp max)")
            orbView?.onExpansionChanged = { expanded ->
                params?.let { p ->
                    p.width = if (expanded) expandedWidthPx else orbSizePx
                    try {
                        windowManager?.updateViewLayout(orbView, p)
                    } catch (e: Exception) {
                        Log.w(TAG, "updateLayout: ${e.message}")
                    }
                }
            }

            val filter = IntentFilter("com.example.app_abdelbaset.ORB_TEXT")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                registerReceiver(orbTextReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(orbTextReceiver, filter)
            }

            handler.post(poller)
        } catch (e: Throwable) {
            Log.e(TAG, "Overlay creation failed", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        handler.removeCallbacks(poller)
        orbView?.animate()?.cancel()
        try { unregisterReceiver(orbTextReceiver) } catch (_: Exception) {}
        try { orbView?.let { windowManager?.removeView(it) } }
        catch (e: Exception) { Log.w(TAG, "removeView: ${e.message}") }
        orbView = null
        params = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val poller = object : Runnable {
        override fun run() {
            val view = orbView ?: return
            val p = params ?: return

//            val isVisible = VisualizerState.orbState != VisualizerState.OrbState.IDLE
//            val wantTouchable = isVisible

//            if (wantTouchable != lastWasTouchable) {
//                lastWasTouchable = wantTouchable
//                p.width = if (wantTouchable) orbSizePx else expandedWidthPx
//                p.flags = if (wantTouchable) {
//                    p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
//                } else {
//                    p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
//                }
//                try {
//                    windowManager?.updateViewLayout(view, p)
//                } catch (e: Exception) {
//                    Log.w(TAG, "updateLayout: ${e.message}")
//                }
//            }

            view.invalidate()
            handler.postDelayed(this, POLL_MS)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(
                CHANNEL_ID, "Hologram Overlay", NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                (getSystemService(NotificationManager::class.java))
                    .createNotificationChannel(this)
            }
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("").setContentText("")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true).setOngoing(true)
            .setContentIntent(pi).build()
    }
}