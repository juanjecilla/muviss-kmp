package com.codingpit.muviss.feature.triage.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.common.flags.TriageControlScheme
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.models.MediaId
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
 * Where a card leaves when a verdict commits — and, played backwards, where an
 * undone card comes back from.
 *
 * The inverse of [verdictFor], and deliberately *not* parameterised by
 * [TriageControlScheme]: the scheme decides which drags are allowed to commit
 * Watching, not what Watching looks like once it has been chosen. A card
 * decided by its button under THREE_WAY still drops downward, because that is
 * the direction the tutorial, the hint and the four-way drag all associate with
 * that verdict.
 */
fun directionFor(verdict: TriageVerdict): DragDirection = when (verdict) {
    TriageVerdict.SKIP -> DragDirection.LEFT
    TriageVerdict.LATER -> DragDirection.RIGHT
    TriageVerdict.CAUGHT_UP -> DragDirection.UP
    TriageVerdict.WATCHING -> DragDirection.DOWN
}

/**
 * The deck's motion, hoisted out of the card that draws it.
 *
 * Hoisted for two reasons. The first is that a card has to be able to *leave*:
 * the offset used to live in [SwipeCard] and was shared by "whichever card is
 * on top", so committing a verdict flung the card out, swapped the next card's
 * content into the same moving layer and flew that one back in — the card never
 * left the screen and the deck appeared to reject every decision. The offset
 * therefore records [owner], the card it belongs to; every other card draws at
 * rest no matter where the animation has parked it. That also removes the
 * frame-ordering hazard in [commit]: by the time the deck advances, the
 * promoted card's id no longer matches, so it renders centred on the very first
 * frame even though the animatable is still off screen.
 *
 * The second is that dragging is not the only way to decide. The verdict
 * buttons and the arrow keys used to call the ViewModel directly and cut
 * straight to the next card; they now go through [commit] as well, so all three
 * inputs produce the same motion.
 *
 * Keeping [owner] and its parked offset *after* a commit is what makes undo
 * free: the restored card is by definition the one that just left, so it is
 * still the owner and still off screen, and [enter] only has to animate it
 * home.
 */
@Stable
class SwipeDeckState internal constructor(animated: Boolean) {

    /**
     * Whether transitions play at all. Direct manipulation is not a transition
     * — a card under a finger keeps tracking it with this off, because that
     * motion *is* the gesture.
     */
    internal var animated: Boolean = animated

    private val rendered = Animatable(Offset.Zero, Offset.VectorConverter)

    private var owner: MediaId? by mutableStateOf(null)

    /**
     * How far the finger has travelled.
     *
     * Tracked separately from [rendered] — which is fed from a coroutine and
     * therefore lags by up to a frame — because deciding which verdict a
     * release commits off that lagged value would make a fast flick read as a
     * short one.
     */
    internal var drag: Offset by mutableStateOf(Offset.Zero)
        private set

    /** True while a card is flying out or coming back; every input path is inert. */
    var busy: Boolean by mutableStateOf(false)
        private set

    /** The verdict being committed, so the card keeps its hint lit on the way out. */
    internal var committing: TriageVerdict? by mutableStateOf(null)
        private set

    private var cardSize: IntSize by mutableStateOf(IntSize.Zero)

    internal fun measured(size: IntSize) {
        cardSize = size
    }

    /** Where [cardId] draws. Anything that is not the [owner] is at rest. */
    fun offsetOf(cardId: MediaId): Offset = if (owner == cardId) rendered.value else Offset.Zero

    internal fun dragged(cardId: MediaId, to: Offset) {
        owner = cardId
        drag = to
    }

    internal suspend fun follow(to: Offset) = rendered.snapTo(to)

    /** A drag let go short of the threshold: back to centre, deciding nothing. */
    internal suspend fun settle(cardId: MediaId) {
        owner = cardId
        drag = Offset.Zero
        if (animated) rendered.animateTo(Offset.Zero, tween(SNAP_BACK_MS)) else rendered.snapTo(Offset.Zero)
    }

    /**
     * Flies [cardId] out for [verdict] and only then runs [onDecided], so the
     * deck advances once the card is actually gone.
     *
     * Re-entrant calls are dropped rather than queued: a second button press
     * during the flight would otherwise decide the *next* card, which the user
     * has not seen yet.
     */
    suspend fun commit(cardId: MediaId, verdict: TriageVerdict, onDecided: () -> Unit) {
        if (busy) return
        owner = cardId
        busy = true
        committing = verdict
        try {
            val target = offScreen(directionFor(verdict), from = rendered.value)
            if (animated) rendered.animateTo(target, tween(EXIT_MS)) else rendered.snapTo(target)
            onDecided()
        } finally {
            // Note the offset is deliberately left parked off screen, and
            // `owner` with it: that is where undo comes back from.
            busy = false
            committing = null
            drag = Offset.Zero
        }
    }

    /** Undo: the card returns along the path it left by. */
    suspend fun enter(cardId: MediaId, direction: DragDirection) {
        busy = true
        try {
            // Normally the restored card is still the owner and already parked
            // off screen. It is not after a refill or a process restart, and
            // then it has to be put there before it can come back.
            if (owner != cardId) {
                owner = cardId
                rendered.snapTo(offScreen(direction, from = Offset.Zero))
            }
            drag = Offset.Zero
            if (animated) rendered.animateTo(Offset.Zero, tween(ENTER_MS)) else rendered.snapTo(Offset.Zero)
        } finally {
            busy = false
        }
    }

    /** A new card must not inherit the previous one's drag. */
    internal fun forgetDrag() {
        drag = Offset.Zero
    }

    /**
     * Far enough that the card is fully clear of the screen, keeping whatever
     * the perpendicular axis already had so a diagonal flick keeps its feel.
     */
    private fun offScreen(direction: DragDirection, from: Offset): Offset {
        val width = cardSize.width.takeIf { it > 0 }?.toFloat() ?: UNMEASURED_TRAVEL_PX
        val height = cardSize.height.takeIf { it > 0 }?.toFloat() ?: UNMEASURED_TRAVEL_PX
        return when (direction) {
            DragDirection.LEFT -> Offset(-travel(width, from.x), from.y)
            DragDirection.RIGHT -> Offset(travel(width, from.x), from.y)
            DragDirection.UP -> Offset(from.x, -travel(height, from.y))
            DragDirection.DOWN -> Offset(from.x, travel(height, from.y))
        }
    }

    /**
     * How far along the axis the card ends up, never less than where it already
     * is: a flick can carry a card past the nominal target, and animating to
     * that target would drag it visibly back toward the centre on the way out.
     */
    private fun travel(extent: Float, current: Float): Float = maxOf(extent * EXIT_TRAVEL, abs(current) + extent * CLEARANCE)
}

/**
 * Remembers the deck's motion for as long as the screen lives — above the
 * `loading`/`empty`/deck branch, so a refill that unmounts the deck does not
 * take a card's flight with it.
 */
@Composable
fun rememberSwipeDeckState(animated: Boolean): SwipeDeckState {
    val state = remember { SwipeDeckState(animated) }
    // Pushed in rather than keyed on, so flipping the Settings toggle takes
    // effect on the next transition instead of discarding the current one.
    SideEffect { state.animated = animated }
    return state
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
 * button per verdict, and this is an accelerator on top of it. A committed drag
 * reports the verdict and stops there — [SwipeDeckState.commit], driven from
 * the screen, owns the flight and the decision that follows it, because the
 * buttons and the arrow keys have to reach exactly the same motion.
 *
 * A tap is a separate `pointerInput` rather than a `clickable`: the card is
 * semantically a draggable surface, and `clickable` would put a `Role.Button`
 * and a full-card ripple on it. A tap never crosses touch slop, so
 * `detectDragGestures` never claims it and the two coexist.
 */
@Composable
fun SwipeCard(
    state: SwipeDeckState,
    cardId: MediaId,
    scheme: TriageControlScheme,
    available: List<TriageVerdict>,
    onDecide: (TriageVerdict) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(pending: TriageVerdict?, progress: Float) -> Unit,
) {
    val scope = rememberCoroutineScope()

    val thresholdPx = with(LocalDensity.current) { COMMIT_THRESHOLD.toPx() }

    // Computed from the drag on every read, never captured: the gesture
    // handlers below live for as long as their `pointerInput` keys do, so a
    // value resolved at composition time would still be the drag's starting
    // state when the finger lifts — and every swipe would read as no swipe.
    fun pendingVerdict(offset: Offset): TriageVerdict? = offset.dominantDirection()?.let { verdictFor(it, scheme, available) }

    fun dragProgress(offset: Offset): Float = (offset.magnitudeAlongDominantAxis() / thresholdPx).coerceIn(0f, 1f)

    // Keyed on the card, not on `available`: two consecutive TV cards have an
    // equal verdict list, so keying on that never fired between them.
    LaunchedEffect(cardId) { state.forgetDrag() }

    Box(
        modifier = modifier
            .onSizeChanged(state::measured)
            .graphicsLayer {
                val offset = state.offsetOf(cardId)
                translationX = offset.x
                translationY = offset.y
                // Capped: the fly-out travels well past the commit threshold,
                // and left uncapped the card would spin as it goes.
                rotationZ = ((offset.x / thresholdPx) * MAX_ROTATION_DEGREES).coerceIn(-MAX_ROTATION_DEGREES * 2, MAX_ROTATION_DEGREES * 2)
            }
            .pointerInput(scheme, available, cardId) {
                detectDragGestures(
                    onDrag = { change, amount ->
                        if (state.busy) return@detectDragGestures
                        change.consume()
                        val to = state.drag + amount
                        state.dragged(cardId, to)
                        scope.launch { state.follow(to) }
                    },
                    onDragEnd = {
                        if (state.busy) return@detectDragGestures
                        val committed = pendingVerdict(state.drag).takeIf { dragProgress(state.drag) >= 1f }
                        if (committed != null) {
                            // Neither the offset nor the drag is reset here: the
                            // flight continues from where the finger left the
                            // card, hint still lit, and `commit` clears both.
                            onDecide(committed)
                        } else {
                            scope.launch { state.settle(cardId) }
                        }
                    },
                    onDragCancel = { scope.launch { state.settle(cardId) } },
                )
            }
            // Keyed the same as the drag detector above: unkeyed (`Unit`),
            // this coroutine never restarted when the top card changed, so a
            // tap kept calling the `onTap` captured for whichever card first
            // composed here — the one just swiped away, not the new top card.
            .pointerInput(scheme, available, cardId) {
                detectTapGestures(onTap = { if (!state.busy) onTap() })
            }
            .fillMaxSize(),
    ) {
        val committing = state.committing
        content(committing ?: pendingVerdict(state.drag), if (committing != null) 1f else dragProgress(state.drag))
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

/** Card-lengths of travel — enough that the card is fully off screen, corners included. */
private const val EXIT_TRAVEL = 1.35f

/** Added to a flick that already went further than [EXIT_TRAVEL], so it still clears the edge. */
private const val CLEARANCE = 0.5f

/** Only ever used before the card has been measured, which in practice never happens. */
private const val UNMEASURED_TRAVEL_PX = 2_000f

/** Out is quicker than back: a decision should feel dispatched, an undo reassuring. */
internal const val EXIT_MS = 220
internal const val ENTER_MS = 280
