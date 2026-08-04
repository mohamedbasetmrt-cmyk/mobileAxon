package com.example.app_abdelbaset

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import com.example.app_abdelbaset.ui.theme.BgPrimary
import com.example.app_abdelbaset.ui.theme.TextPrimary

@SuppressLint("CustomSplashScreen")
class SplashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            MaterialTheme {
                SplashScreen {
                    startActivity(Intent(this@SplashActivity, MainActivity::class.java))
                    finish()
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                }
            }
        }
    }
}

@Composable
fun SplashScreen(onFinished: () -> Unit) {

    // Ring pulse
    val infiniteTransition = rememberInfiniteTransition(label = "ring")
    val ringScale by infiniteTransition.animateFloat(
        initialValue  = 0.90f,
        targetValue   = 1.10f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1600, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ring"
    )

    // حرف حرف
    val letters = listOf("A", "X", "O", "N")
    val letterAlphas = remember { letters.map { Animatable(0f) } }
    val subtitleAlpha = remember { Animatable(0f) }
    val screenAlpha   = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        letters.indices.forEach { i ->
            delay(50L * i)
            letterAlphas[i].animateTo(1f, tween(380, easing = EaseOutQuart))
        }
        delay(10)
        subtitleAlpha.animateTo(1f, tween(600, easing = EaseInOutSine))
        delay(900)
        screenAlpha.animateTo(0f, tween(500, easing = EaseInSine))
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgPrimary)
            .alpha(screenAlpha.value),
        contentAlignment = Alignment.Center
    ) {
        // Ring خلف الكلمة
        Box(
            modifier = Modifier
                .size(240.dp)
                .scale(ringScale)
                .drawBehind {
                    drawCircle(
                        color  = TextPrimary.copy(alpha = 0.08f),
                        radius = size.minDimension / 2f
                    )
                    drawCircle(
                        color  = TextPrimary.copy(alpha = 0.25f),
                        radius = size.minDimension / 2f,
                        style  = Stroke(width = 1.2.dp.toPx())
                    )
                }
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row {
                letters.forEachIndexed { i, letter ->
                    Text(
                        text          = letter,
                        fontSize      = 54.sp,
                        fontWeight    = FontWeight.Bold,
                        color         = TextPrimary,
                        letterSpacing = 10.sp,
                        modifier      = Modifier.alpha(letterAlphas[i].value)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text          = "REALTIME CONTEXT-AWARE AI",
                fontSize      = 10.sp,
                color         = TextPrimary,
                letterSpacing = 4.sp,
                fontWeight    = FontWeight.Medium,
                modifier      = Modifier.alpha(subtitleAlpha.value)
            )
        }
    }
}