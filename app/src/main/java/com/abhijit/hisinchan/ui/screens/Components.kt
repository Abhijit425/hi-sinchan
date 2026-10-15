package com.abhijit.hisinchan.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.abhijit.hisinchan.ui.theme.LocalAmbient
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Slow-drifting gradient blobs. One cheap Canvas, ~1 frame of work per vsync. */
@Composable
fun AmbientBackground(modifier: Modifier = Modifier) {
    val a = LocalAmbient.current
    val t by rememberInfiniteTransition(label = "bg").animateFloat(
        0f, (2 * PI).toFloat(), infiniteRepeatable(tween(40_000, easing = LinearEasing), RepeatMode.Restart), label = "bgT"
    )
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(a.top, a.middle, a.bottom)))
        val w = size.width; val h = size.height; val m = size.maxDimension
        drawCircle(
            Brush.radialGradient(listOf(a.blobA.copy(alpha = 0.55f), Color.Transparent),
                center = Offset(w * (0.2f + 0.08f * cos(t)), h * (0.25f + 0.06f * sin(t))), radius = m * 0.45f),
            radius = m * 0.45f, center = Offset(w * (0.2f + 0.08f * cos(t)), h * (0.25f + 0.06f * sin(t)))
        )
        drawCircle(
            Brush.radialGradient(listOf(a.blobB.copy(alpha = 0.5f), Color.Transparent),
                center = Offset(w * (0.8f + 0.06f * sin(t * 2)), h * (0.75f + 0.07f * cos(t))), radius = m * 0.5f),
            radius = m * 0.5f, center = Offset(w * (0.8f + 0.06f * sin(t * 2)), h * (0.75f + 0.07f * cos(t)))
        )
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val a = LocalAmbient.current
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(a.glass)
            .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(24.dp))
            .padding(padding),
        content = content,
    )
}

/** Shared layout for the secondary screens: ambient background, back button, centred column. */
@Composable
fun SubScreen(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    floating: @Composable BoxScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AmbientBackground()
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).padding(start = 4.dp))
                actions()
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    Modifier.widthIn(max = 860.dp).fillMaxWidth().navigationBarsPadding(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    content = content,
                )
            }
        }
        floating()
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, start = 4.dp))
}

@Composable
fun Dot(color: Color, size: Dp = 10.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(50)).background(color))
}

@Composable
fun Gap(h: Dp) = Spacer(Modifier.size(h))
