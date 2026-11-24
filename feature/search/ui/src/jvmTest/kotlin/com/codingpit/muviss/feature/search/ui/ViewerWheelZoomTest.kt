@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The wheel on a real picture, through Compose's input injection. Each scenario gets a fresh viewer by changing
 * [key]: one test can then compare a single scroll with a sequence, without the two sharing a zoom.
 */
private fun ZoomableState.userZoomValue(): Float = contentTransformation.takeIf { it.isSpecified }?.scaleMetadata?.userZoom ?: 1f

@Composable
private fun Viewport(onState: (ZoomableState, CoroutineScope) -> Unit) {
    val state = rememberZoomableState(ZoomSpec(maxZoomFactor = MAX_USER_ZOOM))
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) { state.setContentLocation(FitContentLocation(Size(300f, 300f))) }
    SideEffect { onState(state, scope) }
    Box(
        Modifier
            .size(300.dp)
            .testTag(VIEWPORT)
            .wheelZooms(state)
            .zoomable(state)
            .background(Color.Gray),
    )
}

private const val VIEWPORT = "viewport"

/** How far one wheel notch zooms: 1.1 of the zoom it starts from (see [wheelZoomFactor]). */
private const val MAX_WHEEL_STEP_ZOOM = 1.1f

class ViewerWheelZoomTest {

    @Test
    fun one_scroll_with_no_modifier_zooms_in_and_settles() = runComposeUiTest {
        val viewer = Viewers(this)

        val zoom = viewer.fresh()
        onNodeWithTag(VIEWPORT).performMouseInput { scroll(-1f) }

        viewer.settle(zoom)
        assertTrue(zoom.userZoomValue() > 1f, "one scroll should zoom in, was ${zoom.userZoomValue()}")
    }

    @Test
    fun two_quick_scrolls_compound_rather_than_one_replacing_the_other() = runComposeUiTest {
        val viewer = Viewers(this)

        val single = viewer.fresh()
        onNodeWithTag(VIEWPORT).performMouseInput { scroll(-1f) }
        viewer.settle(single)
        val oneStep = single.userZoomValue()

        val quick = viewer.fresh()
        onNodeWithTag(VIEWPORT).performMouseInput {
            scroll(-1f)
            scroll(-1f)
        }
        viewer.settle(quick)

        assertTrue(
            abs(quick.userZoomValue() - oneStep * oneStep) < 0.02f,
            "two quick scrolls should compound to ${oneStep * oneStep}, were ${quick.userZoomValue()}",
        )
    }

    @Test
    fun alt_with_the_wheel_is_zoomed_once_not_twice() = runComposeUiTest {
        val viewer = Viewers(this)

        val plain = viewer.fresh()
        onNodeWithTag(VIEWPORT).performMouseInput { scroll(-1f) }
        viewer.settle(plain)
        val oneStep = plain.userZoomValue()

        val alt = viewer.fresh()
        onNodeWithTag(VIEWPORT).performKeyInput { keyDown(Key.AltLeft) }
        onNodeWithTag(VIEWPORT).performMouseInput { scroll(-1f) }
        onNodeWithTag(VIEWPORT).performKeyInput { keyUp(Key.AltLeft) }
        viewer.settle(alt)

        assertTrue(
            abs(alt.userZoomValue() - oneStep) < 0.02f,
            "Alt + wheel should zoom once like the plain wheel ($oneStep), not twice; was ${alt.userZoomValue()}",
        )
    }

    /**
     * A pan from the keyboard, while the wheel's zoom is still animating, takes over that zoom. The wheel must go on
     * zooming afterwards: before the fix the interrupted zoom ended the only consumer, so later scrolls did nothing.
     */
    @Test
    fun a_pan_that_interrupts_a_zoom_leaves_later_scrolls_zooming() = runComposeUiTest {
        val viewer = Viewers(this)

        val zoom = viewer.fresh()
        onNodeWithTag(VIEWPORT).performMouseInput { scroll(-1f) }
        waitUntil(timeoutMillis = 5_000) { zoom.isAnimationRunning }
        // Freeze the clock while the zoom is in flight, so the pan lands in the middle of it and takes it over.
        mainClock.autoAdvance = false
        runOnIdle { checkNotNull(viewer.scope).launch { zoom.panBy(Offset(20f, 0f)) } }
        mainClock.autoAdvance = true
        waitUntil(timeoutMillis = 5_000) { !zoom.isAnimationRunning }
        val afterPan = zoom.userZoomValue()
        assertTrue(
            afterPan < MAX_WHEEL_STEP_ZOOM - 0.01f,
            "the pan should have stopped the zoom short of one wheel step (${MAX_WHEEL_STEP_ZOOM}), but it reached $afterPan",
        )

        onNodeWithTag(VIEWPORT).performMouseInput { scroll(-1f) }
        waitUntil(timeoutMillis = 5_000) { zoom.userZoomValue() > afterPan + 0.01f }
        waitUntil(timeoutMillis = 5_000) { !zoom.isAnimationRunning }

        assertTrue(
            zoom.userZoomValue() > afterPan,
            "a scroll after the pan should still zoom; it stayed at ${zoom.userZoomValue()} after the pan left it at $afterPan",
        )
    }

    /** Switches the test to a new viewer, so each scenario starts at the fitted zoom. */
    private class Viewers(private val test: ComposeUiTest) {
        private var generation by mutableStateOf(0)
        private var current: ZoomableState? = null
        var scope: CoroutineScope? = null
            private set

        init {
            test.setContent {
                key(generation) {
                    Viewport { state, viewportScope ->
                        current = state
                        scope = viewportScope
                    }
                }
            }
        }

        fun fresh(): ZoomableState {
            generation++
            test.waitForIdle()
            return checkNotNull(current)
        }

        fun settle(zoom: ZoomableState) = with(test) {
            waitUntil(timeoutMillis = 5_000) { zoom.userZoomValue() > 1f }
            waitUntil(timeoutMillis = 5_000) { !zoom.isAnimationRunning }
        }
    }
}
