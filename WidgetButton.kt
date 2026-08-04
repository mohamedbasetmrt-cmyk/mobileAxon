package com.example.app_abdelbaset

data class WidgetButton(
    val id: String,
    val label: String,
    val command: String,
    val iconRes: String = "ic_star"  // اسم الـ icon
)