package com.example.app_abdelbaset

data class ContextSection(
    val id: String,
    val title: String,
    val content: String,
    val order: Int,
    val showOnWidget: Boolean = true
)
