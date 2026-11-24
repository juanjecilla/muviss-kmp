package com.codingpit.muviss.feature.search.ui

import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.min

/** The least and most the picture may be zoomed, as Telephoto's user zoom: 1 is fitted to the screen. */
internal const val MIN_USER_ZOOM = 1f
internal const val MAX_USER_ZOOM = 4f

/** One animation the wheel asks Telephoto for: multiply the zoom by [factor], around [focalPoint] in viewport space. */
internal data class WheelZoomStep(val factor: Float, val focalPoint: Offset)

/**
 * The wheel's zoom multiplier for one scroll, or null when the wheel did not move.
 *
 * Scrolling up zooms in and down zooms out, by 10% of the scroll distance per event. Zooming out divides rather than
 * subtracts, so a large trackpad delta can never reach zero or a negative factor, and the step is capped so one fling
 * cannot jump across the whole zoom range.
 */
internal fun wheelZoomFactor(scrollDeltaY: Float): Float? {
    if (scrollDeltaY == 0f) return null
    val step = min(abs(scrollDeltaY) * WHEEL_ZOOM_STEP, MAX_WHEEL_ZOOM_STEP)
    return if (scrollDeltaY < 0f) 1f + step else 1f / (1f + step)
}

/**
 * Turns a stream of wheel scrolls into zoom animations that compound.
 *
 * Telephoto's `zoomBy` reads the zoom when it starts and its animation replaces any one already running, so two scrolls
 * launched on their own would both aim from the same zoom and one would be lost. This keeps the zoom the wheel is
 * heading to, so each scroll multiplies the previous target rather than the zoom currently on screen. Only one
 * animation is in flight at a time: the caller asks for [nextStep], animates it, then calls [settled].
 *
 * The target is clamped to [MIN_USER_ZOOM]..[MAX_USER_ZOOM], and the focal point is the latest scroll's position, which
 * the caller must have taken in viewport coordinates. A pan does not change the target, so the next scroll aims from
 * the zoom the picture is at with the pan kept. Reversing direction takes back the step it undoes.
 */
internal class WheelZoomQueue {
    private var target: Float? = null
    private var focalPoint: Offset = Offset.Zero
    private var requested = false

    /** Records one unconsumed scroll, aimed from the zoom the wheel is heading to, or from [currentZoom] when idle. */
    fun request(currentZoom: Float, scrollDeltaY: Float, at: Offset) {
        val factor = wheelZoomFactor(scrollDeltaY) ?: return
        target = ((target ?: currentZoom) * factor).coerceIn(MIN_USER_ZOOM, MAX_USER_ZOOM)
        focalPoint = at
        requested = true
    }

    /**
     * The animation that takes [currentZoom] to the target, or null when nothing is pending or the zoom already is there.
     * The target is kept until [settled], so a scroll that arrives during the animation still compounds on it.
     */
    fun nextStep(currentZoom: Float): WheelZoomStep? {
        if (!requested) return null
        requested = false
        val goal = target ?: return null
        val factor = goal / currentZoom
        if (abs(factor - 1f) < SAME_ZOOM) {
            target = null
            return null
        }
        return WheelZoomStep(factor, focalPoint)
    }

    /** The animation from [nextStep] has finished. Once nothing new has arrived, the zoom is at rest and the target goes. */
    fun settled() {
        if (!requested) target = null
    }

    /**
     * The animation from [nextStep] was cancelled before it reached its target, because another gesture (a pan from the
     * keyboard) took over. The zoom now is wherever it stopped, so the target it was heading to is no longer a place to
     * aim from. A scroll that arrived meanwhile keeps its place and is applied from the zoom on screen; otherwise the
     * wheel is idle again and the next scroll aims from the picture as it stands.
     */
    fun interrupted() = settled()

    private companion object {
        const val SAME_ZOOM = 1e-4f
    }
}

/**
 * Applies the zoom animations [queue] asks for, one at a time, for as long as the caller is collecting [requests].
 *
 * [animate] is Telephoto's `zoomBy`, and another gesture can cancel it part way: a pan at the same priority replaces it,
 * and the cancellation surfaces here as a [CancellationException]. That ends the animation, not this loop, so the zoom
 * is left where it stopped ([WheelZoomQueue.interrupted]) and the loop keeps taking requests. Cancelling the loop itself
 * (the pointer input leaving composition) still ends it: the cancellation is rethrown, and the loop never reads on.
 */
internal suspend fun applyWheelZooms(
    queue: WheelZoomQueue,
    requests: ReceiveChannel<Unit>,
    currentZoom: () -> Float,
    animate: suspend (WheelZoomStep) -> Unit,
) {
    for (ignored in requests) {
        queue.applyPendingSteps(currentZoom, animate)
    }
}

/** Runs the animations [WheelZoomQueue.nextStep] asks for until nothing is pending. */
private suspend fun WheelZoomQueue.applyPendingSteps(currentZoom: () -> Float, animate: suspend (WheelZoomStep) -> Unit) {
    while (true) {
        val step = nextStep(currentZoom()) ?: return
        try {
            animate(step)
            settled()
        } catch (cancelled: CancellationException) {
            // Only the animation was cancelled while the loop is still wanted: carry on. Once the loop itself is
            // cancelled, its cancellation is what we must propagate.
            if (!currentCoroutineContext().isActive) throw cancelled
            interrupted()
        }
    }
}

private const val WHEEL_ZOOM_STEP = 0.1f
private const val MAX_WHEEL_ZOOM_STEP = 0.5f
