package com.example.app_abdelbaset

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object ContextSectionStore {

    private const val PREF_NAME = "context_sections"
    private const val KEY_SECTIONS = "sections"
    private const val PREF_EXPAND = "context_sections_expand"
    private const val KEY_EXPAND = "expand_map"
    const val MAX_SECTIONS = 20
    const val MAX_WIDGET_SECTIONS = 6

    fun load(context: Context): List<ContextSection> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_SECTIONS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { i ->
                val obj = arr.getJSONObject(i)
                ContextSection(
                    id           = obj.getString("id"),
                    title        = obj.getString("title"),
                    content      = obj.getString("content"),
                    order        = obj.getInt("order"),
                    showOnWidget = obj.optBoolean("showOnWidget", true)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, sections: List<ContextSection>) {
        val arr = JSONArray()
        sections.forEach { s ->
            arr.put(JSONObject().apply {
                put("id",           s.id)
                put("title",        s.title)
                put("content",      s.content)
                put("order",        s.order)
                put("showOnWidget", s.showOnWidget)
            })
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SECTIONS, arr.toString())
            .apply()
        ContextSectionsWidget.updateAllWidgets(context)
    }

    fun add(context: Context, title: String, content: String, showOnWidget: Boolean = true) {
        val current = load(context).toMutableList()
        if (current.size >= MAX_SECTIONS) return
        val maxOrder = current.maxOfOrNull { it.order } ?: -1
        current.add(ContextSection(
            id           = System.currentTimeMillis().toString(),
            title        = title,
            content      = content,
            order        = maxOrder + 1,
            showOnWidget = showOnWidget
        ))
        save(context, current)
    }

    fun update(context: Context, id: String, title: String, content: String, showOnWidget: Boolean) {
        val updated = load(context).map {
            if (it.id == id) it.copy(title = title, content = content, showOnWidget = showOnWidget)
            else it
        }
        save(context, updated)
    }

    fun delete(context: Context, id: String) {
        save(context, load(context).filter { it.id != id })
    }

    fun reorder(context: Context, orderedIds: List<String>) {
        val sections = load(context).toMutableList()
        val newOrder = orderedIds.withIndex().associate { (idx, id) -> id to idx }
        val reordered = sections.map { it.copy(order = newOrder[it.id] ?: it.order) }
            .sortedBy { it.order }
        save(context, reordered)
    }

    fun getWidgetSections(context: Context): List<ContextSection> =
        load(context).filter { it.showOnWidget }.sortedBy { it.order }.take(MAX_WIDGET_SECTIONS)

    fun getWidgetOverflowCount(context: Context): Int =
        (load(context).count { it.showOnWidget } - MAX_WIDGET_SECTIONS).coerceAtLeast(0)

    fun isExpanded(context: Context, id: String): Boolean {
        val prefs = context.getSharedPreferences(PREF_EXPAND, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_EXPAND, "{}") ?: "{}"
        return try {
            JSONObject(json).optBoolean(id, false)
        } catch (_: Exception) { false }
    }

    fun toggleExpandedAccordion(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREF_EXPAND, Context.MODE_PRIVATE)
        val wasExpanded = isExpanded(context, id)
        // Accordion: افتح الـ section ده وقفل الباقي، أو لو كان مفتوح خليه يتقفل
        val newMap = JSONObject()
        if (!wasExpanded) {
            newMap.put(id, true)
        }
        prefs.edit().putString(KEY_EXPAND, newMap.toString()).apply()
    }
}
