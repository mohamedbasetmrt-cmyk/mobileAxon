package com.example.app_abdelbaset

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.app_abdelbaset.ui.theme.*

class ContextSectionEditActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SECTION_ID = "section_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sectionId = intent.getStringExtra(EXTRA_SECTION_ID) ?: run { finish(); return }
        val section = ContextSectionStore.load(this).find { it.id == sectionId }
            ?: run { finish(); return }

        setContent {
            var title by remember { mutableStateOf(section.title) }
            var content by remember { mutableStateOf(section.content) }
            var showOnWidget by remember { mutableStateOf(section.showOnWidget) }

            Dialog(onDismissRequest = { finish() }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(CardBg)
                        .border(0.5.dp, CardBorder, RoundedCornerShape(20.dp))
                        .padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(NeonCyan)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Edit Section",
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(18.dp))

                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title", color = TextMuted, fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = CardBorder,
                            focusedLabelColor = NeonCyan,
                            cursorColor = NeonCyan,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text("Content", color = TextMuted, fontSize = 13.sp) },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = CardBorder,
                            focusedLabelColor = NeonCyan,
                            cursorColor = NeonCyan,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(BgPrimary)
                            .clickable { showOnWidget = !showOnWidget }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Show on Widget", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("Display this section on the home screen widget", color = TextMuted, fontSize = 11.sp)
                        }
                        Switch(
                            checked = showOnWidget,
                            onCheckedChange = { showOnWidget = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = BgPrimary,
                                checkedTrackColor = NeonCyan,
                                uncheckedThumbColor = TextMuted,
                                uncheckedTrackColor = CardBorder
                            )
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { finish() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, CardBorder)
                        ) { Text("Cancel", color = TextMuted) }

                        Button(
                            onClick = {
                                ContextSectionStore.update(
                                    this@ContextSectionEditActivity,
                                    section.id, title, content, showOnWidget
                                )
                                finish()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                            enabled = title.isNotBlank()
                        ) { Text("Save", color = BgPrimary, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}