package com.lifedeck.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.lifedeck.app.util.Celebration
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/**
 * Non-intrusive celebration: a soft overlay that appears for a couple of
 * seconds and dismisses itself (or on tap). Configurable in Settings.
 */
@Composable
fun CelebrationOverlay(
    celebration: Celebration?,
    onDismiss: () -> Unit,
    confettiEnabled: Boolean = true,
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(celebration) {
        if (celebration != null) {
            visible = true
            delay(2600)
            visible = false
            delay(300)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = visible && celebration != null,
        enter = fadeIn(tween(240)) + scaleIn(initialScale = 0.92f, animationSpec = tween(240)),
        exit = fadeOut(tween(320)) + scaleOut(targetScale = 0.96f, animationSpec = tween(320)),
        modifier = Modifier.fillMaxSize().zIndex(100f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable { visible = false },
            contentAlignment = Alignment.Center,
        ) {
            if (confettiEnabled) ConfettiRing()

            celebration?.let { event ->
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 8.dp,
                    tonalElevation = 8.dp,
                    modifier = Modifier.padding(32.dp),
                ) {
                    Column(
                        Modifier.padding(horizontal = 28.dp, vertical = 26.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            when (event.kind) {
                                Celebration.Kind.GOAL -> "🌟"
                                Celebration.Kind.PROJECT -> "🎉"
                                Celebration.Kind.MILESTONE -> "🎯"
                                Celebration.Kind.STREAK -> "🔥"
                                Celebration.Kind.TASK_DONE -> "✨"
                            },
                            style = MaterialTheme.typography.displaySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            event.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            event.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        if (event.kind != Celebration.Kind.TASK_DONE) {
                            Spacer(Modifier.height(14.dp))
                            ProgressBar(
                                fraction = event.progressPercent / 100f,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Tap anywhere to continue",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Gentle, tasteful confetti: a slow rotating ring of soft dots. */
@Composable
private fun ConfettiRing() {
    val transition = rememberInfiniteTransition(label = "confetti")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4000), RepeatMode.Restart),
        label = "confettiProgress",
    )
    val colors = listOf(
        Color(0xFFFFD54F), Color(0xFF4FC3F7), Color(0xFFFF8A80),
        Color(0xFF81C784), Color(0xFFBA68C8), Color(0xFFFF6D9D),
    )
    Box(Modifier.size(300.dp), contentAlignment = Alignment.Center) {
        colors.forEachIndexed { index, color ->
            val angle = (index * 60f) + progress * 360f
            val radius = 110f + sin(((index * 40f + progress * 360f).toDouble()) * Math.PI / 180.0).toFloat() * 18f
            val x = 150f + cos(angle.toDouble() * Math.PI / 180.0).toFloat() * radius
            val y = 150f + sin(angle.toDouble() * Math.PI / 180.0).toFloat() * radius
            Box(
                Modifier
                    .padding(start = x.dp, top = y.dp)
                    .size((10 + index % 3 * 4).dp)
                    .scale(0.7f + 0.3f * sin((progress + index).toDouble() * Math.PI).toFloat())
                    .rotate(angle)
                    .clip(RoundedCornerShape(if (index % 2 == 0) 4.dp else 50.dp))
                    .background(color.copy(alpha = 0.85f)),
            )
        }
    }
}
