package com.codingpit.muviss.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Tags the burst overlay so a test can tell whether it is on screen (EPIC 51). */
const val CELEBRATION_BURST_TAG = "celebrationBurst"

private const val PARTICLE_COUNT = 32
private const val DURATION_MILLIS = 900

/** In dp: a burst is the same size on a 2x phone and a 3x one. */
private const val GRAVITY = 260f
private const val SEED = 51

/** One dot of the burst: which way it leaves the centre, how fast, and how big. */
private class Particle(val angle: Float, val speed: Float, val radius: Float, val colorIndex: Int)

/**
 * A short celebration for a milestone: a burst of dots from the middle of the
 * overlay and a pill saying [label], gone after about a second. [onFinished]
 * fires when it is over, so the caller can drop it.
 *
 * Hand-drawn on a [Canvas] rather than a library: it has to run on every
 * target, wasm included, and it is 24 dots. The particles come from a seeded
 * [Random], so a screenshot of a given frame is the same everywhere.
 *
 * It takes no touches and hides its content from the accessibility tree: the
 * caller says the same thing in a snackbar, which is what a screen reader
 * announces, and a motion-free setting skips the burst but keeps that text.
 */
@Composable
fun CelebrationBurst(
    label: String,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = remember { Animatable(0f) }
    val finished = rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(DURATION_MILLIS, easing = LinearEasing))
        finished.value()
    }
    val particles = remember {
        val random = Random(SEED)
        List(PARTICLE_COUNT) {
            Particle(
                angle = random.nextFloat() * TWO_PI,
                speed = 90f + random.nextFloat() * 130f,
                radius = 5f + random.nextFloat() * 6f,
                colorIndex = it % 3,
            )
        }
    }
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary)

    Box(modifier.fillMaxSize().clearAndSetSemantics { testTag = CELEBRATION_BURST_TAG }) {
        Canvas(Modifier.fillMaxSize()) {
            val t = progress.value
            val seconds = t * DURATION_MILLIS / 1000f
            val origin = Offset(size.width / 2f, size.height / 2f)
            // Fast out, then fall: a dot's speed is spent in the first moments and gravity takes over.
            val travel = 1f - (1f - t) * (1f - t)
            val fall = 0.5f * GRAVITY.dp.toPx() * seconds * seconds
            particles.forEach { particle ->
                val reach = particle.speed.dp.toPx() * travel
                val x = origin.x + cos(particle.angle) * reach
                val y = origin.y + sin(particle.angle) * reach + fall
                drawCircle(
                    color = colors[particle.colorIndex].copy(alpha = (1f - t).coerceIn(0f, 1f)),
                    radius = particle.radius.dp.toPx() / 2f * (1f - 0.4f * t),
                    center = Offset(x, y),
                )
            }
        }
        // Not a Surface: a non-clickable one swallows touches, and this must not.
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer { alpha = pillAlpha(progress.value) }
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = MuvissSpacing.l, vertical = MuvissSpacing.s),
        )
    }
}

private const val TWO_PI = 6.2831855f

/** In over the first tenth, out over the last third. */
private fun pillAlpha(t: Float): Float = when {
    t < 0.1f -> t / 0.1f
    t > 0.67f -> ((1f - t) / 0.33f).coerceIn(0f, 1f)
    else -> 1f
}
