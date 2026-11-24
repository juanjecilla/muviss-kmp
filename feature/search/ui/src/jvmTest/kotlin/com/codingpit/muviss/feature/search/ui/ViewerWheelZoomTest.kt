@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import kotlin.test.Test
import kotlin.test.assertTrue

/** An unmodified mouse wheel zooms the picture under it. Telephoto's own shortcut needs Alt, so this one is ours. */
class ViewerWheelZoomTest {

    @Test
    fun scrolling_up_with_no_modifier_zooms_in() = runComposeUiTest {
        var zoom: ZoomableState? = null
        setContent {
            val state = rememberZoomableState(ZoomSpec(maxZoomFactor = 4f))
            val scope = rememberCoroutineScope()
            LaunchedEffect(state) { state.setContentLocation(FitContentLocation(Size(300f, 300f))) }
            SideEffect { zoom = state }
            Box(
                Modifier
                    .size(300.dp)
                    .testTag(VIEWPORT)
                    .zoomable(state)
                    .wheelZooms(state, scope)
                    .background(Color.Gray),
            )
        }
        waitForIdle()

        onNodeWithTag(VIEWPORT).performMouseInput { scroll(-3f) }

        waitUntil(timeoutMillis = 5_000) { (zoom?.zoomFraction ?: 0f) > 0f }
        assertTrue((zoom?.zoomFraction ?: 0f) > 0f)
    }

    private companion object {
        const val VIEWPORT = "viewport"
    }
}
