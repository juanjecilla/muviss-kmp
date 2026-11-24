@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test

/**
 * The layout half of the immersive status bar, on the real list and the real tracking modifier. Density is pinned to 1,
 * so a dp and a px are the same number. The hero is 200 dp of artwork plus the bar; the Skiko renderer reports no insets,
 * so the test passes the bar's height itself, as [ImmersiveStatusBar]'s parameter exists for.
 *
 * What is asserted is the band ([IMMERSIVE_BAR_TAG]): it is present exactly while artwork touches the bar. The light
 * icons are an Android window call and cannot be observed here; they are gated by the same `covered` state.
 */
class ImmersiveStatusBarTest {

    @Test
    fun the_band_is_present_at_rest_when_the_hero_covers_the_bar() = runComposeUiTest {
        setContent { HeroScreenUnderTest(topInset = 24.dp, offsetPx = 0) }

        onAllNodesWithTag(IMMERSIVE_BAR_TAG).assertCountEquals(1)
    }

    @Test
    fun the_band_stays_just_inside_the_last_offset_that_touches_the_bar() = runComposeUiTest {
        // The hero is 224 dp with a 24 dp bar, so its bottom leaves the bar at offset 224; 223 still touches it.
        setContent { HeroScreenUnderTest(topInset = 24.dp, offsetPx = 223) }

        onAllNodesWithTag(IMMERSIVE_BAR_TAG).assertCountEquals(1)
    }

    @Test
    fun the_band_and_the_icons_go_once_the_hero_has_cleared_the_bar() = runComposeUiTest {
        setContent { HeroScreenUnderTest(topInset = 24.dp, offsetPx = 224) }

        onAllNodesWithTag(IMMERSIVE_BAR_TAG).assertCountEquals(0)
    }

    @Test
    fun a_bar_of_zero_never_shows_the_band() = runComposeUiTest {
        setContent { HeroScreenUnderTest(topInset = 0.dp, offsetPx = 0) }

        onAllNodesWithTag(IMMERSIVE_BAR_TAG).assertCountEquals(0)
    }

    @Test
    fun the_band_follows_a_swipe_away_and_back() = runComposeUiTest {
        setContent { HeroScreenUnderTest(topInset = 24.dp, offsetPx = 0) }

        onNodeWithTag(LIST_TAG).performTouchInput { swipeUp() }
        waitForIdle()
        onAllNodesWithTag(IMMERSIVE_BAR_TAG).assertCountEquals(0)

        onNodeWithTag(LIST_TAG).performTouchInput { swipeDown() }
        waitForIdle()
        onAllNodesWithTag(IMMERSIVE_BAR_TAG).assertCountEquals(1)
    }

    @Test
    fun a_changed_inset_recomputes_without_any_scroll() = runComposeUiTest {
        // At 230 px the 48 dp hero (248 dp) still covers the bar; once the bar is 24 dp (224 dp) the list is past it.
        // The list starts at 230 px, so the first item is still composed; the change alone clears the band.
        var inset by mutableStateOf(48.dp)
        setContent { ScrolledArtworkUnderTest(artworkHeight = { 200.dp }, topInset = { inset }, offsetPx = 230) }
        onAllNodesWithTag(IMMERSIVE_BAR_TAG).assertCountEquals(1)

        inset = 24.dp
        waitForIdle()
        onAllNodesWithTag(IMMERSIVE_BAR_TAG).assertCountEquals(0)
    }

    @Test
    fun a_changed_width_recomputes_a_still_without_any_scroll() = runComposeUiTest {
        // A 16:9 still is 0.5625 * width tall, so at 180 px it covers a 300 dp screen (192.75 dp) but not a 200 dp one.
        var width by mutableStateOf(300.dp)
        setContent { ScrolledArtworkUnderTest(artworkHeight = { width * 9f / 16f }, topInset = { 24.dp }, offsetPx = 180) }
        onAllNodesWithTag(IMMERSIVE_BAR_TAG).assertCountEquals(1)

        width = 200.dp
        waitForIdle()
        onAllNodesWithTag(IMMERSIVE_BAR_TAG).assertCountEquals(0)
    }

    @Test
    fun no_artwork_means_no_band_so_loading_and_error_keep_the_theme_icons() = runComposeUiTest {
        // The loading and error branches render no artwork, so the screen's tracker stays empty.
        setContent {
            val artwork = remember { ImmersiveArtwork() }
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalImmersiveArtwork provides artwork) {
                Box(Modifier.fillMaxWidth().height(300.dp)) {
                    ImmersiveStatusBar(artwork, topInset = 24.dp, Modifier.align(Alignment.TopCenter))
                }
            }
        }

        onAllNodesWithTag(IMMERSIVE_BAR_TAG).assertCountEquals(0)
    }

    @Composable
    private fun HeroScreenUnderTest(topInset: Dp, offsetPx: Int) {
        val artwork = remember { ImmersiveArtwork() }
        CompositionLocalProvider(LocalDensity provides Density(1f), LocalImmersiveArtwork provides artwork) {
            val listState = rememberLazyListState(initialFirstVisibleItemScrollOffset = offsetPx)
            Box(Modifier.size(300.dp)) {
                LazyColumn(Modifier.fillMaxWidth().height(300.dp).testTag(LIST_TAG), state = listState) {
                    item {
                        Box(Modifier.fillMaxWidth().height(200.dp + topInset).trackImmersiveArtwork())
                    }
                    items(50) { Box(Modifier.fillMaxWidth().height(60.dp)) }
                }
                ImmersiveStatusBar(artwork, topInset, Modifier.align(Alignment.TopCenter))
            }
        }
    }

    /**
     * Artwork in a plain `verticalScroll`, not a `LazyColumn`: a scroll state is only pixels, so a change of height
     * recomputes at the same offset, where a lazy list re-anchors its first item and moves the subject away.
     */
    @Composable
    private fun ScrolledArtworkUnderTest(artworkHeight: () -> Dp, topInset: () -> Dp, offsetPx: Int) {
        val artwork = remember { ImmersiveArtwork() }
        CompositionLocalProvider(LocalDensity provides Density(1f), LocalImmersiveArtwork provides artwork) {
            val scroll = rememberScrollState(initial = offsetPx)
            Box(Modifier.size(300.dp)) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    Box(Modifier.fillMaxWidth().height(artworkHeight() + topInset()).trackImmersiveArtwork())
                    repeat(50) { Box(Modifier.fillMaxWidth().height(60.dp)) }
                }
                ImmersiveStatusBar(artwork, topInset(), Modifier.align(Alignment.TopCenter))
            }
        }
    }
}

private const val LIST_TAG = "immersiveStatusBarList"
