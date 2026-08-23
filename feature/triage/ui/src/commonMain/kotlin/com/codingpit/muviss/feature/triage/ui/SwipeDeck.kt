package com.codingpit.muviss.feature.triage.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.common.flags.TriageControlScheme
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Which way the card is being pulled, once it has moved far enough to mean anything. */
enum class DragDirection { LEFT, RIGHT, UP, DOWN }

/**
 * Maps a drag direction to a verdict.
 *
 * Left/right/up carry the three high-frequency verdicts under both schemes.
 * The difference is down: [TriageControlScheme.FOUR_WAY] gives it Watching,
 * [TriageControlScheme.THREE_WAY] leaves it inert so the least discoverable
 * direction cannot mis-fire the verdict that writes ticks — there, Watching is
 * reached by its button.
 *
 * Returns null when the direction means nothing for this card, which includes
 * every downward drag on a movie: a film is never partway through, so Watching
 * is not offered for one under either scheme.
 */
fun verdictFor(
    direction: DragDirection,
    scheme: TriageControlScheme,
    available: List<TriageVerdict>,
): TriageVerdict? {
    val verdict = when (direction) {
        DragDirection.LEFT -> TriageVerdict.SKIP
        DragDirection.RIGHT -> TriageVerdict.LATER
        DragDirection.UP -> TriageVerdict.CAUGHT_UP
        DragDirection.DOWN -> TriageVerdict.WATCHING.takeIf { scheme == TriageControlScheme.FOUR_WAY }
    }
    return verdict?.takeIf { it in available }
}

/**
 * A draggable card with a directional hint.
 *
 * Built on `pointerInput` from `compose.foundation` rather than a library —
 * the repo has no gesture infrastructure and takes no new UI dependencies. The
 * same code drives touch on Android/iOS and mouse on desktop and web; the
 * keyboard path lives on the screen, not here.
 *
 * The card is never the only way to decide: [TriageScreen] renders a labelled
 * button per verdict, and this is an accelerator on top of it.
 *
 * Drag distance is tracked in plain state, not in the [Animatable] that draws
 * it. The animatable is updated from a coroutine and therefore lags by up to a
 * frame; deciding which verdict a release commits off that lagged value would
 * mean a fast flick reads as a short one.
 */
@Composable
fun SwipeCard(
    scheme: TriageControlScheme,
    available: List<TriageVerdict>,
    onDecide: (TriageVerdict) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(pending: TriageVerdict?, progress: Float) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val rendered = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var drag by remember { mutableStateOf(Offset.Zero) }

    val thresholdPx = with(LocalDensity.current) { COMMIT_THRESHOLD.toPx() }

    // Computed from `drag` on every read, never captured: the gesture handlers
    // below live for as long as their `pointerInput` keys do, so a value
    // resolved at composition time would still be the drag's starting state
    // when the finger lifts — and every swipe would read as no swipe at all.
    fun pendingVerdict(offset: Offset): TriageVerdict? = offset.dominantDirection()?.let { verdictFor(it, scheme, available) }

    fun dragProgress(offset: Offset): Float = (offset.magnitudeAlongDominantAxis() / thresholdPx).coerceIn(0f, 1f)

    // A new card must not inherit the previous one's drag.
    LaunchedEffect(available) {
        drag = Offset.Zero
        rendered.snapTo(Offset.Zero)
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                translationX = rendered.value.x
                translationY = rendered.value.y
                rotationZ = (rendered.value.x / thresholdPx) * MAX_ROTATION_DEGREES
            }
            .pointerInput(scheme, available) {
                detectDragGestures(
                    onDrag = { change, amount ->
                        change.consume()
                        drag += amount
                        scope.launch { rendered.snapTo(drag) }
                    },
                    onDragEnd = {
                        val committed = pendingVerdict(drag).takeIf { dragProgress(drag) >= 1f }
                        drag = Offset.Zero
                        // Snap back either way: on commit the card is replaced,
                        // and a stale offset would show the next card already
                        // flung aside.
                        scope.launch { rendered.animateTo(Offset.Zero, tween(SNAP_BACK_MS)) }
                        if (committed != null) onDecide(committed)
                    },
                    onDragCancel = {
                        drag = Offset.Zero
                        scope.launch { rendered.animateTo(Offset.Zero, tween(SNAP_BACK_MS)) }
                    },
                )
            }
            .fillMaxSize(),
    ) {
        content(pendingVerdict(drag), dragProgress(drag))
    }
}

/**
 * The axis the drag is mostly on. Comparing absolute values keeps a diagonal
 * from firing two verdicts at once — the larger component wins, and a perfect
 * diagonal resolves horizontally, the pair of directions users reach for first.
 */
private fun Offset.dominantDirection(): DragDirection? = when {
    x == 0f && y == 0f -> null
    abs(x) >= abs(y) -> if (x < 0) DragDirection.LEFT else DragDirection.RIGHT
    else -> if (y < 0) DragDirection.UP else DragDirection.DOWN
}

private fun Offset.magnitudeAlongDominantAxis(): Float = maxOf(abs(x), abs(y))

/** Far enough to be deliberate, close enough to flick. */
private val COMMIT_THRESHOLD = 120.dp
private const val MAX_ROTATION_DEGREES = 8f
private const val SNAP_BACK_MS = 180
