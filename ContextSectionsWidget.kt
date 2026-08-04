package com.example.app_abdelbaset

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.widget.RemoteViews

class ContextSectionsWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE_EXPAND = "com.example.app_abdelbaset.CONTEXT_TOGGLE_EXPAND"
        const val EXTRA_SECTION_ID = "section_id"

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, ContextSectionsWidget::class.java)
            )
            if (ids.isNotEmpty()) {
                val intent = Intent(context, ContextSectionsWidget::class.java).apply {
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
        when (intent.action) {
            ACTION_TOGGLE_EXPAND -> {
                val sectionId = intent.getStringExtra(EXTRA_SECTION_ID) ?: return
                ContextSectionStore.toggleExpandedAccordion(context, sectionId)
                updateAllWidgets(context)
            }
        }
    }

    private fun updateWidget(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_context_sections)

        val rowIds = listOf(
            R.id.row_1, R.id.row_2, R.id.row_3,
            R.id.row_4, R.id.row_5, R.id.row_6
        )
        val titleIds = listOf(
            R.id.title_1, R.id.title_2, R.id.title_3,
            R.id.title_4, R.id.title_5, R.id.title_6
        )
        val contentIds = listOf(
            R.id.content_1, R.id.content_2, R.id.content_3,
            R.id.content_4, R.id.content_5, R.id.content_6
        )
        val editIds = listOf(
            R.id.edit_1, R.id.edit_2, R.id.edit_3,
            R.id.edit_4, R.id.edit_5, R.id.edit_6
        )

        // Header "+" always opens the app to add a new section
        views.setOnClickPendingIntent(R.id.header_add, makeOpenAppIntent(context, 98))

        val sections = ContextSectionStore.getWidgetSections(context)
        val overflowCount = ContextSectionStore.getWidgetOverflowCount(context)

        if (sections.isEmpty()) {
            // Empty state: centered "+ Add Section" prompt on row 1
            views.setViewVisibility(rowIds[0], android.view.View.VISIBLE)
            views.setInt(rowIds[0], "setBackgroundResource", R.drawable.widget_row_bg)

            views.setTextViewText(titleIds[0], "＋  Add Section")
            views.setInt(titleIds[0], "setGravity", Gravity.CENTER)
            views.setViewVisibility(titleIds[0], android.view.View.VISIBLE)
            views.setViewVisibility(contentIds[0], android.view.View.GONE)
            views.setViewVisibility(editIds[0], android.view.View.GONE)
            views.setOnClickPendingIntent(rowIds[0], makeOpenAppIntent(context, 99))

            for (i in 1 until rowIds.size) {
                views.setViewVisibility(rowIds[i], android.view.View.GONE)
            }
            views.setViewVisibility(R.id.summary_text, android.view.View.GONE)
        } else {
            sections.forEachIndexed { i, section ->
                views.setViewVisibility(rowIds[i], android.view.View.VISIBLE)

                val isExpanded = ContextSectionStore.isExpanded(context, section.id)

                // Highlight the currently expanded card with a cyan-tinted background
                views.setInt(
                    rowIds[i],
                    "setBackgroundResource",
                    if (isExpanded) R.drawable.widget_row_bg_expanded else R.drawable.widget_row_bg
                )

                val title = if (isExpanded) "▾  ${section.title}" else "▸  ${section.title}"

                views.setTextViewText(titleIds[i], title)
                views.setInt(titleIds[i], "setGravity", Gravity.CENTER_VERTICAL or Gravity.START)
                views.setViewVisibility(titleIds[i], android.view.View.VISIBLE)

                if (isExpanded && section.content.isNotBlank()) {
                    views.setTextViewText(contentIds[i], section.content)
                    views.setViewVisibility(contentIds[i], android.view.View.VISIBLE)
                } else {
                    views.setViewVisibility(contentIds[i], android.view.View.GONE)
                }

                views.setViewVisibility(editIds[i], android.view.View.VISIBLE)

                // Toggle expand
                views.setOnClickPendingIntent(
                    titleIds[i],
                    makeToggleIntent(context, section.id, 100 + i)
                )

                // Open edit activity
                views.setOnClickPendingIntent(
                    editIds[i],
                    makeEditActivityIntent(context, section.id, 200 + i)
                )
            }

            // Hide remaining rows
            for (i in sections.size until rowIds.size) {
                views.setViewVisibility(rowIds[i], android.view.View.GONE)
            }

            // Overflow / Add summary
            if (overflowCount > 0) {
                views.setTextViewText(R.id.summary_text, "+$overflowCount more")
                views.setViewVisibility(R.id.summary_text, android.view.View.VISIBLE)
                views.setOnClickPendingIntent(R.id.summary_text, makeOpenAppIntent(context, 90))
            } else {
                views.setViewVisibility(R.id.summary_text, android.view.View.GONE)
            }
        }

        manager.updateAppWidget(widgetId, views)
    }

    private fun makeToggleIntent(context: Context, sectionId: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, ContextSectionsWidget::class.java).apply {
            action = ACTION_TOGGLE_EXPAND
            putExtra(EXTRA_SECTION_ID, sectionId)
        }
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun makeEditActivityIntent(context: Context, sectionId: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, ContextSectionEditActivity::class.java).apply {
            putExtra(ContextSectionEditActivity.EXTRA_SECTION_ID, sectionId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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