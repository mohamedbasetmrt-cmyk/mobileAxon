package com.example.app_abdelbaset

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.app_abdelbaset.ui.theme.*
import com.example.app_abdelbaset.ui.theme.BgPrimary
import com.example.app_abdelbaset.ui.theme.BgSecondary
import com.example.app_abdelbaset.ui.theme.CardBg
import com.example.app_abdelbaset.ui.theme.CardBg2
import com.example.app_abdelbaset.ui.theme.CardBorder
import com.example.app_abdelbaset.ui.theme.CardBorder2
import com.example.app_abdelbaset.ui.theme.TextPrimary
import com.example.app_abdelbaset.ui.theme.TextMuted
import com.example.app_abdelbaset.ui.theme.NeonGreen
import com.example.app_abdelbaset.ui.theme.NeonCyan
import com.example.app_abdelbaset.ui.theme.AccentPink
import com.example.app_abdelbaset.ui.theme.AccentAmber
@Composable
fun WidgetManagerScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChat:     () -> Unit = {},
    onOpenPair:     () -> Unit = {}
) {
    val context = LocalContext.current
    var buttons  by remember { mutableStateOf(WidgetButtonStore.load(context)) }
    var showDialog  by remember { mutableStateOf(false) }
    var editTarget  by remember { mutableStateOf<WidgetButton?>(null) }

    fun refresh() { buttons = WidgetButtonStore.load(context) }

    Box(modifier = Modifier.fillMaxSize().background(BgPrimary)) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

            // ── Header ───────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CardBg2)
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Widget Buttons", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("${buttons.size} / ${WidgetButtonStore.MAX_BUTTONS}", fontSize = 12.sp, color = TextMuted)
                }
                // Add button
                if (buttons.size < WidgetButtonStore.MAX_BUTTONS) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(NeonCyan.copy(alpha = 0.15f))
                            .border(0.5.dp, NeonCyan.copy(0.4f), RoundedCornerShape(8.dp))
                            .clickable { editTarget = null; showDialog = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add", tint = NeonCyan, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // ── Empty state ──────────────────────────────────────────
            if (buttons.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No buttons yet", color = TextMuted, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Tap + to add your first quick action", color = TextMuted.copy(0.6f), fontSize = 12.sp)
                    }
                }
            }

            // ── Buttons list ─────────────────────────────────────────
            buttons.forEachIndexed { index, btn ->
                ButtonRow(
                    btn     = btn,
                    index   = index + 1,
                    onEdit  = { editTarget = btn; showDialog = true },
                    onDelete = {
                        WidgetButtonStore.delete(context, btn.id)
                        refresh()
                    }
                )
                if (index < buttons.lastIndex) {
                    Divider(
                        color = CardBorder,
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            Spacer(Modifier.height(100.dp))
        }

        // ── Bottom Nav ───────────────────────────────────────────────
        AxonAxonBottomNav(
            selectedTab      = 4,
            isServiceRunning = false,
            onListenClick    = onBack,
            onSettingsClick  = onOpenSettings,
            onChatClick      = onOpenChat,
            onWidgetClick    = {},
            onPairClick      = onOpenPair,
            modifier         = Modifier.align(Alignment.BottomCenter)
        )
    }

    // ── Add / Edit Dialog ────────────────────────────────────────────
    if (showDialog) {
        ButtonDialog(
            existing = editTarget,
            onDismiss = { showDialog = false },
            onSave = { label, command, iconRes ->
                if (editTarget != null) {
                    WidgetButtonStore.update(context, editTarget!!.id, label, command, iconRes)
                } else {
                    WidgetButtonStore.add(context, label, command, iconRes)
                }
                refresh()
                showDialog = false
            }
        )

    }
}
// ── Button Row ─────────────────────────────────────────────────────────

@Composable
private fun ButtonRow(
    btn:      WidgetButton,
    index:    Int,
    onEdit:   () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBg)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Index badge
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(NeonCyan.copy(0.1f))
                .border(0.5.dp, NeonCyan.copy(0.3f), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("$index", color = NeonCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(btn.label, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(
                text     = btn.command,
                color    = TextMuted,
                fontSize = 12.sp,
                maxLines = 1
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = "Edit", tint = NeonCyan, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = AccentPink, modifier = Modifier.size(18.dp))
        }
    }
}

// ── Add / Edit Dialog ──────────────────────────────────────────────────

@Composable
private fun ButtonDialog(
    existing:  WidgetButton?,
    onDismiss: () -> Unit,
    onSave: (label: String, command: String, iconRes: String) -> Unit
) {
    var label    by remember { mutableStateOf(existing?.label   ?: "") }
    var command  by remember { mutableStateOf(existing?.command ?: "") }
    var selectedIcon by remember { mutableStateOf(existing?.iconRes ?: "ic_star") }
    var showIconDropdown by remember { mutableStateOf(false) }

    val icons = listOf(
        "ic_star"     to "⭐  Star",
        "ic_email"    to "📧  Email",
        "ic_call"     to "📞  Call",
        "ic_search"   to "🔍  Search",
        "ic_home"     to "🏠  Home",
        "ic_settings" to "⚙️  Settings",
        "ic_mic"      to "🎙  Mic",
        "ic_chat"     to "💬  Chat",
        "ic_alarm"    to "⏰  Alarm",
        "ic_camera"   to "📷  Camera"
    )

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CardBg)
                .border(0.5.dp, CardBorder, RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Text(
                text       = if (existing != null) "Edit Button" else "New Button",
                color      = TextPrimary,
                fontSize   = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value         = label,
                onValueChange = { label = it },
                label         = { Text("Label (shown on widget)", color = TextMuted, fontSize = 13.sp) },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth(),
                colors        = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = NeonCyan,
                    unfocusedBorderColor = CardBorder,
                    focusedLabelColor    = NeonCyan,
                    cursorColor          = NeonCyan,
                    focusedTextColor     = TextPrimary,
                    unfocusedTextColor   = TextPrimary
                )
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value         = command,
                onValueChange = { command = it },
                label         = { Text("AI Command", color = TextMuted, fontSize = 13.sp) },
                minLines      = 3,
                maxLines      = 5,
                modifier      = Modifier.fillMaxWidth(),
                colors        = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = NeonCyan,
                    unfocusedBorderColor = CardBorder,
                    focusedLabelColor    = NeonCyan,
                    cursorColor          = NeonCyan,
                    focusedTextColor     = TextPrimary,
                    unfocusedTextColor   = TextPrimary
                )
            )
            Spacer(Modifier.height(12.dp))

            // ── Icon Dropdown ────────────────────────────────────────
            Text("Icon", color = TextMuted, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))

            Box(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(CardBg2)
                        .border(0.5.dp, if (showIconDropdown) NeonCyan else CardBorder, RoundedCornerShape(8.dp))
                        .clickable { showIconDropdown = true }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text     = icons.find { it.first == selectedIcon }?.second ?: "⭐  Star",
                            color    = TextPrimary,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text("▾", color = TextMuted, fontSize = 14.sp)
                    }
                }

                DropdownMenu(
                    expanded          = showIconDropdown,
                    onDismissRequest  = { showIconDropdown = false },
                    modifier          = Modifier.background(CardBg2)
                ) {
                    icons.forEach { (key, label2) ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text     = label2,
                                    color    = if (selectedIcon == key) NeonCyan else TextPrimary,
                                    fontSize = 14.sp
                                )
                            },
                            onClick = {
                                selectedIcon = key
                                showIconDropdown = false
                            },
                            modifier = Modifier.background(
                                if (selectedIcon == key) NeonCyan.copy(0.1f) else Color.Transparent
                            )
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick  = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape    = RoundedCornerShape(10.dp),
                    border   = androidx.compose.foundation.BorderStroke(0.5.dp, CardBorder)
                ) { Text("Cancel", color = TextMuted) }

                Button(
                    onClick  = { if (label.isNotBlank() && command.isNotBlank()) onSave(label, command, selectedIcon) },
                    modifier = Modifier.weight(1f),
                    shape    = RoundedCornerShape(10.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                    enabled  = label.isNotBlank() && command.isNotBlank()
                ) { Text("Save", color = BgPrimary, fontWeight = FontWeight.Bold) }
            }
        }
    }
}