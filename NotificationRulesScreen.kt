package com.example.app_abdelbaset

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.app_abdelbaset.ui.theme.*
import com.example.app_abdelbaset.ui.theme.BgPrimary
import com.example.app_abdelbaset.ui.theme.BgSecondary
import com.example.app_abdelbaset.ui.theme.CardBg
import com.example.app_abdelbaset.ui.theme.CardBorder
import com.example.app_abdelbaset.ui.theme.TextPrimary
import com.example.app_abdelbaset.ui.theme.TextMuted
import com.example.app_abdelbaset.ui.theme.NeonGreen
import com.example.app_abdelbaset.ui.theme.NeonCyan
import com.example.app_abdelbaset.ui.theme.AccentPink
import com.example.app_abdelbaset.ui.theme.AppFontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationRulesScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var masterEnabled by remember {
        mutableStateOf(NotificationAnnounceManager.isMasterEnabled())
    }
    var rules by remember {
        mutableStateOf(NotificationAnnounceManager.getRules())
    }
    var recentApps by remember {
        mutableStateOf(AxonNotificationListener.getRecentUniquePackages())
    }

    // حالة الـ collapse/expand لكل rule
    var expandedRules by remember { mutableStateOf(setOf<String>()) }

    // Dialog state
    var showEditDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<NotificationRule?>(null) }
    var isNewRule by remember { mutableStateOf(false) }
    var newPkgName by remember { mutableStateOf("") }

    fun refresh() {
        rules = NotificationAnnounceManager.getRules()
        recentApps = AxonNotificationListener.getRecentUniquePackages()
        masterEnabled = NotificationAnnounceManager.isMasterEnabled()
    }

    fun toggleExpand(pkg: String) {
        expandedRules = if (expandedRules.contains(pkg)) {
            expandedRules - pkg
        } else {
            expandedRules + pkg
        }
    }

    fun getAppLabel(pkg: String): String {
        return try {
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) { pkg }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgPrimary)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // ── Header ──────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = NeonGreen
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "NOTIFICATIONS",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        letterSpacing = 4.sp,
                        fontFamily = AppFontFamily
                    )
                    Text(
                        text = ":: ANNOUNCE RULES",
                        fontSize = 10.sp,
                        color = NeonGreen.copy(alpha = 0.7f),
                        letterSpacing = 3.sp,
                        fontFamily = AppFontFamily
                    )
                }
            }

            // ── Master Switch Card ──────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(CardBg)
                    .border(0.5.dp, if (masterEnabled) NeonGreen.copy(0.5f) else CardBorder, RoundedCornerShape(8.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "NOTIFICATION ANNOUNCE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            letterSpacing = 2.sp,
                            fontFamily = AppFontFamily
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (masterEnabled) "Voice announcements active"
                            else "All announcements disabled",
                            fontSize = 9.sp,
                            color = if (masterEnabled) NeonGreen.copy(0.8f) else TextMuted,
                            fontFamily = AppFontFamily
                        )
                    }
                    Switch(
                        checked = masterEnabled,
                        onCheckedChange = {
                            NotificationAnnounceManager.setMasterEnabled(it, context)
                            masterEnabled = it
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NeonGreen,
                            checkedTrackColor = NeonGreen.copy(0.3f),
                            uncheckedThumbColor = TextMuted,
                            uncheckedTrackColor = CardBorder
                        )
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Rules Section Header ────────────────────────────────
            Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ACTIVE RULES",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    letterSpacing = 3.sp,
                    fontFamily = AppFontFamily
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(NeonGreen.copy(0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${rules.size}",
                        fontSize = 9.sp,
                        color = NeonGreen,
                        fontFamily = AppFontFamily
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── Rules List (Fixed Height with Internal Scroll) ──────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(BgSecondary)
                    .border(0.5.dp, CardBorder, RoundedCornerShape(8.dp))
            ) {
                if (rules.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(30.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "> NO RULES CONFIGURED",
                            fontSize = 10.sp,
                            color = TextMuted,
                            fontFamily = AppFontFamily
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp)
                    ) {
                        rules.forEachIndexed { index, rule ->
                            CollapsibleRuleCard(
                                rule = rule,
                                isExpanded = expandedRules.contains(rule.packageName),
                                onToggleExpand = { toggleExpand(rule.packageName) },
                                onToggle = {
                                    val updated = rule.copy(enabled = !rule.enabled)
                                    NotificationAnnounceManager.addOrUpdateRule(updated, context)
                                    refresh()
                                },
                                onEdit = {
                                    editingRule = rule
                                    isNewRule = false
                                    showEditDialog = true
                                },
                                onDelete = {
                                    NotificationAnnounceManager.removeRule(rule.packageName, context)
                                    refresh()
                                },
                                onTest = {
                                    testAnnouncement(context, rule)
                                }
                            )
                            if (index < rules.lastIndex) {
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Manual Add ──────────────────────────────────────────
            Row(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ADD MANUALLY",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    letterSpacing = 3.sp,
                    fontFamily = AppFontFamily
                )
            }

            Spacer(Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(CardBg)
                    .border(0.5.dp, CardBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    OutlinedTextField(
                        value = newPkgName,
                        onValueChange = { newPkgName = it },
                        placeholder = {
                            Text(
                                "com.example.app",
                                fontSize = 9.sp,
                                color = TextMuted.copy(.5f),
                                fontFamily = AppFontFamily
                            )
                        },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 10.sp,
                            fontFamily = AppFontFamily
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonGreen.copy(.6f),
                            unfocusedBorderColor = CardBorder,
                            cursorColor = NeonGreen,
                            focusedLabelColor = NeonGreen
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(Modifier.width(8.dp))

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (newPkgName.isNotBlank())
                                    NeonGreen.copy(.15f)
                                else
                                    CardBorder.copy(.25f)
                            )
                            .border(
                                0.5.dp,
                                if (newPkgName.isNotBlank())
                                    NeonGreen.copy(.4f)
                                else
                                    CardBorder,
                                RoundedCornerShape(4.dp)
                            )
                            .clickable(enabled = newPkgName.isNotBlank()) {
                                val label = getAppLabel(newPkgName.trim())

                                NotificationAnnounceManager.addOrUpdateRule(
                                    NotificationRule(
                                        packageName = newPkgName.trim(),
                                        appLabel = label
                                    ),
                                    context
                                )

                                newPkgName = ""
                                refresh()
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            "ADD",
                            fontSize = 9.sp,
                            color = if (newPkgName.isNotBlank())
                                NeonGreen
                            else
                                TextMuted,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            fontFamily = AppFontFamily
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Add From Recent Apps ────────────────────────────────
            if (recentApps.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ADD FROM RECENT APPS",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted,
                        letterSpacing = 3.sp,
                        fontFamily = AppFontFamily
                    )
                }

                Spacer(Modifier.height(10.dp))

                recentApps.forEach { (pkg, label) ->
                    if (rules.any { it.packageName == pkg }) return@forEach

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(CardBg)
                            .border(0.5.dp, CardBorder, RoundedCornerShape(6.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    fontFamily = AppFontFamily
                                )
                                Text(
                                    text = pkg,
                                    fontSize = 8.sp,
                                    color = TextMuted,
                                    fontFamily = AppFontFamily
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(NeonGreen.copy(0.15f))
                                    .border(0.5.dp, NeonGreen.copy(0.4f), RoundedCornerShape(4.dp))
                                    .clickable {
                                        val newRule = NotificationRule(
                                            packageName = pkg,
                                            appLabel = label
                                        )
                                        NotificationAnnounceManager.addOrUpdateRule(newRule, context)
                                        refresh()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "ADD",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NeonGreen,
                                    letterSpacing = 2.sp,
                                    fontFamily = AppFontFamily
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    // ── Edit Dialog ─────────────────────────────────────────────────
    if (showEditDialog && editingRule != null) {
        EditRuleDialog(
            rule = editingRule!!,
            isNew = isNewRule,
            onDismiss = { showEditDialog = false },
            onSave = { updatedRule ->
                NotificationAnnounceManager.addOrUpdateRule(updatedRule, context)
                showEditDialog = false
                refresh()
            }
        )
    }
}

// ══════════════════════════════════════════════════════════════════════
//  Collapsible Rule Card
// ══════════════════════════════════════════════════════════════════════
@Composable
private fun CollapsibleRuleCard(
    rule: NotificationRule,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit
) {
    val borderColor = when {
        rule.enabled && isExpanded -> NeonGreen.copy(0.5f)
        rule.enabled && !isExpanded -> NeonGreen.copy(0.3f)
        else -> CardBorder
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(CardBg)
            .border(0.5.dp, borderColor, RoundedCornerShape(6.dp))
            .clickable { onToggleExpand() }
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
            .padding(12.dp)
    ) {
        Column {
            // ── Collapsed Row: App name + Switch + Arrow ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status dot
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (rule.enabled) NeonGreen else TextMuted.copy(0.4f))
                )
                Spacer(Modifier.width(10.dp))

                // App name
                Text(
                    text = rule.appLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (rule.enabled) TextPrimary else TextMuted,
                    fontFamily = AppFontFamily,
                    modifier = Modifier.weight(1f)
                )

                // Switch
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = {
                        onToggle()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = NeonGreen,
                        checkedTrackColor = NeonGreen.copy(0.3f),
                        uncheckedThumbColor = TextMuted.copy(0.5f),
                        uncheckedTrackColor = CardBorder
                    ),
                    modifier = Modifier.height(24.dp)
                )

                Spacer(Modifier.width(8.dp))

                // Expand/Collapse arrow
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp
                    else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = if (isExpanded) NeonCyan else TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }

            // ── Expanded Content ──
            if (isExpanded) {
                Spacer(Modifier.height(12.dp))

                // Package name
                Text(
                    text = rule.packageName,
                    fontSize = 8.sp,
                    color = TextMuted.copy(0.7f),
                    fontFamily = AppFontFamily
                )

                Spacer(Modifier.height(10.dp))

                // Template preview label
                Text(
                    text = "TEMPLATE:",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    letterSpacing = 2.sp,
                    fontFamily = AppFontFamily
                )

                Spacer(Modifier.height(4.dp))

                // Template preview box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(BgPrimary)
                        .border(0.5.dp, NeonCyan.copy(0.2f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = rule.template,
                        fontSize = 9.sp,
                        color = NeonCyan.copy(if (rule.enabled) 0.8f else 0.3f),
                        fontFamily = AppFontFamily,
                        maxLines = 3
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    RuleActionButton("TEST", AccentAmber.copy(0.8f)) { onTest() }
                    Spacer(Modifier.width(6.dp))
                    RuleActionButton("EDIT", NeonCyan.copy(0.8f)) { onEdit() }
                    Spacer(Modifier.width(6.dp))
                    RuleActionButton("DELETE", AccentPink.copy(0.8f)) { onDelete() }
                }
            }
        }
    }
}

@Composable
private fun RuleActionButton(
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(tint.copy(0.1f))
            .border(0.5.dp, tint.copy(0.4f), RoundedCornerShape(3.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            color = tint,
            letterSpacing = 2.sp,
            fontFamily = AppFontFamily
        )
    }
}

// ══════════════════════════════════════════════════════════════════════
//  Edit Rule Dialog
// ══════════════════════════════════════════════════════════════════════
@Composable
private fun EditRuleDialog(
    rule: NotificationRule,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (NotificationRule) -> Unit
) {
    var template by remember { mutableStateOf(rule.template) }

    val preview = remember(template) {
        NotificationAnnounceManager.buildPreviewText(template)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        shape = RoundedCornerShape(10.dp),
        title = {
            Text(
                text = if (isNew) "ADD RULE" else "EDIT RULE",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                letterSpacing = 3.sp,
                fontFamily = AppFontFamily
            )
        },
        text = {
            Column {
                Text(
                    text = rule.appLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeonGreen,
                    fontFamily = AppFontFamily
                )
                Text(
                    text = rule.packageName,
                    fontSize = 8.sp,
                    color = TextMuted,
                    fontFamily = AppFontFamily
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    text = "ANNOUNCEMENT TEMPLATE:",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    letterSpacing = 2.sp,
                    fontFamily = AppFontFamily
                )

                Spacer(Modifier.height(6.dp))

                OutlinedTextField(
                    value = template,
                    onValueChange = { template = it },
                    placeholder = {
                        Text(
                            "you have notification from {app}: {title}",
                            fontSize = 10.sp,
                            color = TextMuted.copy(0.4f),
                            fontFamily = AppFontFamily
                        )
                    },
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 11.sp,
                        fontFamily = AppFontFamily,
                        color = TextPrimary
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonGreen.copy(0.6f),
                        unfocusedBorderColor = CardBorder,
                        cursorColor = NeonGreen
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "Placeholders: {app}  {title}  {content}",
                    fontSize = 8.sp,
                    color = NeonCyan.copy(0.6f),
                    fontFamily = AppFontFamily
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = "PREVIEW:",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    letterSpacing = 2.sp,
                    fontFamily = AppFontFamily
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(BgPrimary)
                        .border(0.5.dp, NeonGreen.copy(0.2f), RoundedCornerShape(4.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = if (preview.isNotBlank()) "\"$preview\"" else "(empty)",
                        fontSize = 10.sp,
                        color = NeonGreen.copy(0.8f),
                        fontFamily = AppFontFamily
                    )
                }
            }
        },
        confirmButton = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(NeonGreen.copy(0.15f))
                    .border(0.5.dp, NeonGreen.copy(0.5f), RoundedCornerShape(4.dp))
                    .clickable {
                        if (template.isNotBlank()) {
                            onSave(rule.copy(template = template))
                        }
                    }
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "SAVE",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeonGreen,
                    letterSpacing = 2.sp,
                    fontFamily = AppFontFamily
                )
            }
        },
        dismissButton = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(CardBorder.copy(0.2f))
                    .clickable { onDismiss() }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "CANCEL",
                    fontSize = 10.sp,
                    color = TextMuted,
                    letterSpacing = 2.sp,
                    fontFamily = AppFontFamily
                )
            }
        }
    )
}

// ══════════════════════════════════════════════════════════════════════
//  Test Announcement Helper
// ══════════════════════════════════════════════════════════════════════
private fun testAnnouncement(context: android.content.Context, rule: NotificationRule) {
    val speechText = NotificationAnnounceManager.buildSpeechText(
        rule, "Test Title", "Test Content", rule.appLabel
    )
    java.util.concurrent.Executors.newSingleThreadExecutor().execute {
        val engine = LocalTtsEngine(context)
        try {
            if (engine.init()) {
                val latch = java.util.concurrent.CountDownLatch(1)
                engine.speak(speechText, isLast = true) { latch.countDown() }
                latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
            }
        } catch (e: Exception) {
            android.util.Log.e("RuleTest", "TTS error: ${e.message}")
        } finally {
            engine.release()
        }
    }
}