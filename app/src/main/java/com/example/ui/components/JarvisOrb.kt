package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.example.ai.AssistantState
import com.example.ui.theme.JarvisCyan
import com.example.ui.theme.JarvisEmerald
import com.example.ui.theme.JarvisIndigo
import com.example.ui.theme.JarvisSky
import com.example.ui.theme.JarvisViolet
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Gemini-Live-inspired smooth, fluid, glowing animated AI Orb that reacts organically
 * to voice amplitude and assistant state (IDLE, LISTENING, THINKING, SPEAKING, CONTROLLING).
 */
@Composable
fun JarvisOrb(
    state: AssistantState,
    amplitude: Float,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "jarvis_orb")

    val primaryRotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "primary_rot"
    )

    val secondaryRotation by infinite.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 13500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "secondary_rot"
    )

    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "fluid_phase"
    )

    val breath by infinite.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath"
    )

    val smoothAmp by animateFloatAsState(
        targetValue = amplitude.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 260f),
        label = "smooth_amp"
    )

    val coreColor by animateColorAsState(
        targetValue = when (state) {
            AssistantState.IDLE -> JarvisSky
            AssistantState.LISTENING -> JarvisCyan
            AssistantState.THINKING -> JarvisViolet
            AssistantState.SPEAKING -> Color(0xFF67E8F9)
            AssistantState.CONTROLLING -> JarvisEmerald
        },
        animationSpec = tween(420),
        label = "core_color"
    )

    val haloColor by animateColorAsState(
        targetValue = when (state) {
            AssistantState.IDLE -> JarvisIndigo
            AssistantState.LISTENING -> JarvisSky
            AssistantState.THINKING -> JarvisIndigo
            AssistantState.SPEAKING -> JarvisCyan
            AssistantState.CONTROLLING -> JarvisCyan
        },
        animationSpec = tween(420),
        label = "halo_color"
    )

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val center = Offset(cx, cy)
        val maxRadius = min(size.width, size.height) / 2f

        val energyBoost = when (state) {
            AssistantState.IDLE -> 0.04f
            AssistantState.LISTENING -> 0.14f + smoothAmp * 0.24f
            AssistantState.THINKING -> 0.16f
            AssistantState.SPEAKING -> 0.16f + smoothAmp * 0.28f
            AssistantState.CONTROLLING -> 0.14f
        }

        val baseRadius = maxRadius * 0.44f * breath * (1f + energyBoost * 0.45f)

        // 1. Deep atmospheric nebula outer glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    coreColor.copy(alpha = 0.34f + smoothAmp * 0.18f),
                    haloColor.copy(alpha = 0.14f),
                    Color.Transparent
                ),
                center = center,
                radius = maxRadius * 1.05f
            ),
            radius = maxRadius * 1.05f,
            center = center
        )

        // 2. Reactive ripple ring during Listening / Speaking
        if (state == AssistantState.LISTENING || state == AssistantState.SPEAKING) {
            val rippleProgress = ((phase / (2.0 * PI)).toFloat())
            val rippleRadius = baseRadius * (1.15f + rippleProgress * (0.65f + smoothAmp * 0.35f))
            val rippleAlpha = (1f - rippleProgress) * (0.35f + smoothAmp * 0.3f)
            drawCircle(
                color = coreColor.copy(alpha = rippleAlpha.coerceIn(0f, 0.7f)),
                radius = rippleRadius.coerceAtMost(maxRadius * 0.98f),
                center = center,
                style = Stroke(width = 2.2f)
            )
        }

        // 3. Organic fluid sine-perturbed plasma layers
        val wobbleStrength = when (state) {
            AssistantState.IDLE -> 0.055f
            AssistantState.LISTENING -> 0.10f + smoothAmp * 0.16f
            AssistantState.THINKING -> 0.14f
            AssistantState.SPEAKING -> 0.12f + smoothAmp * 0.20f
            AssistantState.CONTROLLING -> 0.09f
        }

        rotate(degrees = primaryRotation, pivot = center) {
            val outerFluidPath = buildFluidBlobPath(
                center = center,
                baseRadius = baseRadius * 1.18f,
                phase = phase,
                harmonics = 3,
                wobble = wobbleStrength
            )
            drawPath(
                path = outerFluidPath,
                brush = Brush.sweepGradient(
                    colors = listOf(
                        coreColor.copy(alpha = 0.55f),
                        haloColor.copy(alpha = 0.30f),
                        JarvisCyan.copy(alpha = 0.60f),
                        coreColor.copy(alpha = 0.55f)
                    ),
                    center = center
                )
            )
        }

        rotate(degrees = secondaryRotation, pivot = center) {
            val innerFluidPath = buildFluidBlobPath(
                center = center,
                baseRadius = baseRadius * 1.04f,
                phase = -phase * 1.3f,
                harmonics = 4,
                wobble = wobbleStrength * 0.85f
            )
            drawPath(
                path = innerFluidPath,
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.85f),
                        coreColor.copy(alpha = 0.78f),
                        haloColor.copy(alpha = 0.42f),
                        Color.Transparent
                    ),
                    center = Offset(cx - baseRadius * 0.16f, cy - baseRadius * 0.18f),
                    radius = baseRadius * 1.35f
                )
            )
        }

        // 4. Luminous crisp core sphere
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.92f),
                    coreColor.copy(alpha = 0.88f),
                    haloColor.copy(alpha = 0.45f)
                ),
                center = Offset(cx - baseRadius * 0.14f, cy - baseRadius * 0.16f),
                radius = baseRadius * 0.92f
            ),
            radius = baseRadius * 0.78f,
            center = center
        )

        // 5. Precision HUD orbital accent arc (intensifies while Thinking or Controlling)
        val arcAlpha = when (state) {
            AssistantState.THINKING -> 0.85f
            AssistantState.CONTROLLING -> 0.90f
            AssistantState.SPEAKING -> 0.55f
            AssistantState.LISTENING -> 0.50f
            AssistantState.IDLE -> 0.25f
        }
        val orbitSpeed = if (state == AssistantState.THINKING) primaryRotation * 2.2f else primaryRotation
        rotate(degrees = orbitSpeed, pivot = center) {
            val orbitR = baseRadius * 1.36f
            drawArc(
                color = coreColor.copy(alpha = arcAlpha),
                startAngle = 0f,
                sweepAngle = 72f,
                useCenter = false,
                topLeft = Offset(cx - orbitR, cy - orbitR),
                size = androidx.compose.ui.geometry.Size(orbitR * 2f, orbitR * 2f),
                style = Stroke(width = 3.2f, cap = StrokeCap.Round)
            )
            drawArc(
                color = haloColor.copy(alpha = arcAlpha * 0.75f),
                startAngle = 180f,
                sweepAngle = 56f,
                useCenter = false,
                topLeft = Offset(cx - orbitR, cy - orbitR),
                size = androidx.compose.ui.geometry.Size(orbitR * 2f, orbitR * 2f),
                style = Stroke(width = 2.6f, cap = StrokeCap.Round)
            )
        }
    }
}

private fun buildFluidBlobPath(
    center: Offset,
    baseRadius: Float,
    phase: Float,
    harmonics: Int,
    wobble: Float
): Path {
    val steps = 72
    val path = Path()
    for (i in 0..steps) {
        val angle = (i.toFloat() / steps) * (2.0 * PI).toFloat()
        val wave = sin(angle * harmonics + phase) * 0.6f +
            cos(angle * (harmonics + 2) - phase * 1.4f) * 0.4f
        val r = baseRadius * (1f + wave * wobble)
        val x = center.x + cos(angle) * r
        val y = center.y + sin(angle) * r
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}
