package com.example.app_abdelbaset

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object WidgetButtonStore {

    private const val PREF_NAME = "widget_buttons"
    private const val KEY_BUTTONS = "buttons"
    const val MAX_BUTTONS = 16

    fun load(context: Context): List<WidgetButton> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_BUTTONS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { i ->
                val obj = arr.getJSONObject(i)
                WidgetButton(
                    id      = obj.getString("id"),
                    label   = obj.getString("label"),
                    command = obj.getString("command"),
                    iconRes = obj.optString("iconRes", "ic_star")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, buttons: List<WidgetButton>) {
        val arr = JSONArray()
        buttons.forEach { btn ->
            arr.put(JSONObject().apply {
                put("id",      btn.id)
                put("label",   btn.label)
                put("command", btn.command)
                put("iconRes", btn.iconRes)

            })
        }
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BUTTONS, arr.toString())
            .apply()
        AxonQuickWidget.updateAllWidgets(context)
    }

    fun add(context: Context, label: String, command: String, iconRes: String = "ic_star") {
        val current = load(context).toMutableList()
        if (current.size >= MAX_BUTTONS) return
        current.add(WidgetButton(
            id      = System.currentTimeMillis().toString(),
            label   = label,
            command = command,
            iconRes = iconRes
        ))
        save(context, current)
    }

    fun update(context: Context, id: String, label: String, command: String, iconRes: String = "ic_star") {
        val updated = load(context).map {
            if (it.id == id) it.copy(label = label, command = command,iconRes = iconRes) else it
        }
        save(context, updated)
    }

    fun delete(context: Context, id: String) {
        save(context, load(context).filter { it.id != id })
    }
}