package com.example.app_abdelbaset

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews

/**
 * ═══════════════════════════════════════════════════════════════════
 *  AxonCircleWidget — ويدجت الدايرة
 * ═══════════════════════════════════════════════════════════════════
 *
 *  الشكل:
 *   ┌─────────────────────────────┐
 *   │  (wallpaper شفاف)           │
 *   │       •  ← نقطة cyan        │  ← تظهر لما يكون running
 *   │     ╭──────╮               │
 *   │     │  ▶   │               │  ← دايرة سوداء
 *   │     │START │               │
 *   │     ╰──────╯               │
 *   │  (wallpaper شفاف)           │
 *   └─────────────────────────────┘
 *
 *  الـ interaction:
 *   - Click على الدايرة → يشغّل أو يوقف MicForegroundService
 *   - بعد التغيير بـ 500ms يعمل refresh للشكل
 *
 *  الـ update flow:
 *   onReceive(ACTION_TOGGLE) → toggle service → updateWidget()
 *   MicForegroundService.kt  → يستدعي updateAllWidgets() لما الحالة تتغير
 */
class AxonCircleWidget : AppWidgetProvider() {

    companion object {
        // الـ action اللي بنبعته لما المستخدم يضغط على الدايرة
        private const val ACTION_TOGGLE = "com.example.app_abdelbaset.CIRCLE_WIDGET_TOGGLE"

        /**
         * استدعيها من MicForegroundService عشان تحدّث كل widgets
         * مثال في الـ service:
         *   AxonCircleWidget.updateAllWidgets(applicationContext)
         */
        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, AxonCircleWidget::class.java)
            )
            if (ids.isNotEmpty()) {
                // بعت broadcast للـ provider يعمل onUpdate
                val intent = Intent(context, AxonCircleWidget::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  onUpdate — بيتستدعى:
    //    1. أول ما المستخدم يضيف الـ widget
    //    2. بعد reboot
    //    3. لما نبعت ACTION_APPWIDGET_UPDATE يدوياً
    // ─────────────────────────────────────────────────────────────────
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  onReceive — بيستقبل كل الـ broadcasts اللي بتيجي للـ provider
    // ─────────────────────────────────────────────────────────────────
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent) // مهم — بيعمل route لـ onUpdate وغيره

        if (intent.action == ACTION_TOGGLE) {
            handleToggle(context)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  handleToggle — شغّل أو وقّف الـ service
    // ─────────────────────────────────────────────────────────────────
    private fun handleToggle(context: Context) {
        val isCurrentlyRunning = MicForegroundService.isRunning

        if (isCurrentlyRunning) {
            // ── وقّف الـ service ──────────────────────────────────────
            context.stopService(Intent(context, MicForegroundService::class.java))
        } else {
            // ── شغّل الـ service ──────────────────────────────────────
            val serviceIntent = Intent(context, MicForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }

        // انتظر شوية عشان الـ service يتشغل/يتوقف فعلاً، وبعدين حدّث الشكل
        Handler(Looper.getMainLooper()).postDelayed({
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, AxonCircleWidget::class.java)
            )
            for (id in ids) updateWidget(context, manager, id)
        }, 500L)
    }

    // ─────────────────────────────────────────────────────────────────
    //  updateWidget — بيرسم شكل الـ widget حسب الحالة الحالية
    // ─────────────────────────────────────────────────────────────────
    private fun updateWidget(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int
    ) {
        val isRunning = MicForegroundService.isRunning
        val views = RemoteViews(context.packageName, R.layout.widget_circle)

        // ── الـ PendingIntent للـ click ───────────────────────────────
        val toggleIntent = Intent(context, AxonCircleWidget::class.java).apply {
            action = ACTION_TOGGLE
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // اربط الـ click بالـ root (الـ layout كله قابل للضغط)
        views.setOnClickPendingIntent(R.id.widget_circle_root, pendingIntent)

        // ── حدّث الشكل حسب الحالة ────────────────────────────────────
        if (isRunning) {
            // الحالة: شغّال ← دايرة cyan + رمز STOP + نقطة
            views.setImageViewResource(R.id.widget_circle_bg, R.drawable.widget_circle_running)
//            views.setTextViewText(R.id.widget_btn_symbol, "■")
//            views.setTextColor(R.id.widget_btn_symbol, Color.WHITE)
            views.setTextViewText(R.id.widget_btn_label, "STOP")
            views.setTextColor(R.id.widget_btn_label, Color.parseColor("#FEFCFF"))
            views.setViewVisibility(R.id.widget_status_dot, View.VISIBLE)
        } else {
            // الحالة: واقف ← دايرة عادية + رمز START
            views.setImageViewResource(R.id.widget_circle_bg, R.drawable.widget_circle_idle)
//            views.setTextViewText(R.id.widget_btn_symbol, "▶")
//            views.setTextColor(R.id.widget_btn_symbol, Color.WHITE)
            views.setTextViewText(R.id.widget_btn_label, "START")
            views.setTextColor(R.id.widget_btn_label, Color.parseColor("#FEFCFF"))
            views.setViewVisibility(R.id.widget_status_dot, View.INVISIBLE)
        }

        // ── ابعت الـ RemoteViews للـ AppWidgetManager ─────────────────
        manager.updateAppWidget(widgetId, views)
    }
}