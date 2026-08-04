package com.example.app_abdelbaset

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.util.Log
/**
 * ═══════════════════════════════════════════════════════════════════
 *  AxonQuickWidget — ويدجت الـ Quick Actions
 * ═══════════════════════════════════════════════════════════════════
 *
 *  الشكل:
 *   ╭──────────────────────────────────────────╮
 *   │  [🎙 Listen]  [💬 Chat]    [📷 Camera]   │
 *   │  [⚙ Settings] [📋 Log]    [✦  More]     │
 *   ╰──────────────────────────────────────────╯
 *
 *  الحالة الحالية: UI فقط
 *  ─────────────────────────────────────────────────────────────────
 *  كل button دلوقتي بيفتح الـ MainActivity فقط.
 *  لاحقاً: كل button هيعمل action مختلف
 *  (مثلاً btn_listen يشغّل الـ service مباشرة، btn_chat يفتح ChatScreen)
 *
 *  عشان تضيف functionality لـ button معين:
 *  1. روح الـ addButtonActions() تحت
 *  2. ابعت Intent مختلف للـ PendingIntent بتاعه
 *  3. اعمل handle في onReceive() أو في Activity/Service
 */
class AxonQuickWidget : AppWidgetProvider() {

    companion object {
        // ─── Actions للـ buttons (هتتستخدم لاحقاً) ─────────────────
        const val ACTION_LISTEN   = "com.example.app_abdelbaset.QUICK_LISTEN"
        const val ACTION_CHAT     = "com.example.app_abdelbaset.QUICK_CHAT"
        const val ACTION_CAMERA   = "com.example.app_abdelbaset.QUICK_CAMERA"
        const val ACTION_SETTINGS = "com.example.app_abdelbaset.QUICK_SETTINGS"
        const val ACTION_LOG      = "com.example.app_abdelbaset.QUICK_LOG"
        const val ACTION_MORE     = "com.example.app_abdelbaset.QUICK_MORE"

        const val ACTION_EMAILS = "com.example.app_abdelbaset.QUICK_EMAILS"

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, AxonQuickWidget::class.java)
            )
            if (ids.isNotEmpty()) {
                val intent = Intent(context, AxonQuickWidget::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        // ─── TODO: هنا هتضيف الـ handling لكل button لاحقاً ──────────
        when (intent.action) {
            ACTION_LISTEN -> {
                // TODO: toggle MicForegroundService
                openApp(context)
            }
            ACTION_CHAT -> {
                // TODO: فتح ChatScreen مباشرة
                openApp(context)
            }
            ACTION_CAMERA -> {
                // TODO: فتح الكاميرا
                openApp(context)
            }
            ACTION_SETTINGS -> {
                // TODO: فتح Settings screen
                openApp(context)
            }
            ACTION_LOG -> {
                // TODO: فتح Error Log
                openApp(context)
            }
            ACTION_MORE -> {
                // TODO: قائمة إضافية
                openApp(context)
            }
        }
    }
    private fun getIconRes(iconName: String): Int = when (iconName) {
        "ic_email"    -> android.R.drawable.ic_dialog_email
        "ic_call"     -> android.R.drawable.ic_menu_call
        "ic_search"   -> android.R.drawable.ic_menu_search
        "ic_home"     -> android.R.drawable.ic_menu_compass
        "ic_settings" -> android.R.drawable.ic_menu_manage
        "ic_mic"      -> android.R.drawable.ic_btn_speak_now
        "ic_chat"     -> android.R.drawable.ic_menu_send
        "ic_alarm"    -> android.R.drawable.ic_lock_idle_alarm
        "ic_camera"   -> android.R.drawable.ic_menu_camera
        else          -> android.R.drawable.btn_star_big_on
    }
    private fun updateWidget(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int
    ) {
        Log.d("QuickWidget", "updateWidget called for id: $widgetId")
        val views = RemoteViews(context.packageName, R.layout.widget_quick)
        Log.d("QuickWidget", "RemoteViews created OK")
        addButtonActions(context, views)
        manager.updateAppWidget(widgetId, views)
        Log.d("QuickWidget", "updateAppWidget done")
    }

    // ─────────────────────────────────────────────────────────────────
    //  addButtonActions — بيربط كل button بـ PendingIntent
    //
    //  دلوقتي: كل الـ buttons بيفتحوا الـ app
    //  لاحقاً: كل button هيعمل action مختلف
    // ─────────────────────────────────────────────────────────────────
    private fun addButtonActions(context: Context, views: RemoteViews) {
        val buttons = WidgetButtonStore.load(context)

        val allButtonIds = listOf<Int>(
            R.id.btn_1, R.id.btn_2, R.id.btn_3, R.id.btn_4,
            R.id.btn_5, R.id.btn_6, R.id.btn_7, R.id.btn_8,
            R.id.btn_9, R.id.btn_10, R.id.btn_11, R.id.btn_12,
            R.id.btn_13, R.id.btn_14, R.id.btn_15, R.id.btn_16
        )
//        val allLabelIds = listOf<Int>(
//            R.id.label_1, R.id.label_2, R.id.label_3, R.id.label_4,
//            R.id.label_5, R.id.label_6, R.id.label_7, R.id.label_8,
//            R.id.label_9, R.id.label_10, R.id.label_11, R.id.label_12,
//            R.id.label_13, R.id.label_14, R.id.label_15, R.id.label_16
//        )
//        val allIconIds = listOf<Int>(
//            R.id.icon_1, R.id.icon_2, R.id.icon_3, R.id.icon_4,
//            R.id.icon_5, R.id.icon_6, R.id.icon_7, R.id.icon_8,
//            R.id.icon_9, R.id.icon_10, R.id.icon_11, R.id.icon_12,
//            R.id.icon_13, R.id.icon_14, R.id.icon_15, R.id.icon_16
//        )

        if (buttons.isEmpty()) {
            // placeholder لما مفيش buttons
            views.setTextViewText(allButtonIds[0], "+ Add")
            views.setViewVisibility(allButtonIds[0], android.view.View.VISIBLE)
            views.setOnClickPendingIntent(allButtonIds[0], makeOpenAppIntent(context, 99))
            for (i in 1 until allButtonIds.size) {
                views.setViewVisibility(allButtonIds[i], android.view.View.GONE)
            }
        } else {
            buttons.forEachIndexed { i, btn ->
//                val iconRes = getIconRes(btn.iconRes)
//                views.setImageViewResource(allIconIds[i], iconRes)
                views.setTextViewText(allButtonIds[i], btn.label)
                views.setViewVisibility(allButtonIds[i], android.view.View.VISIBLE)
                views.setOnClickPendingIntent(
                    allButtonIds[i],
                    makeWidgetCommandIntent(context, btn.command, 100 + i)
                )
            }
            for (i in buttons.size until allButtonIds.size) {
                views.setViewVisibility(allButtonIds[i], android.view.View.GONE)
            }
        }
    }
    // ─────────────────────────────────────────────────────────────────
    //  helper — بيعمل PendingIntent بيبعت Broadcast
    // ─────────────────────────────────────────────────────────────────
    private fun makeBroadcastIntent(
        context: Context,
        action: String,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, AxonQuickWidget::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ─────────────────────────────────────────────────────────────────
    //  helper — فتح الـ MainActivity (fallback مؤقت)
    // ─────────────────────────────────────────────────────────────────
    private fun openApp(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    private fun makeWidgetCommandIntent(
        context: Context,
        commandText: String,
        requestCode: Int
    ): PendingIntent {
        // لو الـ service شغال → ابعت command مباشرة
        // لو مش شغال → شغّل الـ service الأول
        return if (MicForegroundService.isRunning) {
            val intent = Intent(MicForegroundService.ACTION_WIDGET_COMMAND).apply {
                setPackage(context.packageName)
                putExtra(MicForegroundService.EXTRA_COMMAND_TEXT, commandText)
            }
            PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            // شغّل الـ service + ابعت الـ command بعد ما يشتغل
            // الـ service لما يشتغل هيستنى الـ broadcast
            val startIntent = Intent(context, MicForegroundService::class.java).apply {
                putExtra(MicForegroundService.EXTRA_COMMAND_TEXT, commandText)
            }
            PendingIntent.getService(
                context, requestCode, startIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    private fun makeOpenAppIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}