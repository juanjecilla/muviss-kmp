@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test

/**
 * Which way the status-bar icons should lean, for the two immersive screens. The hero is 400 dp in a 300 dp
 * viewport so it can scroll; the scrim extent is the same 56 dp in both, and density is pinned to 1 so px == dp.
 * The flag text is "under" while the scrim covers the bar and "over" once it has scrolled away.
 */
class ImmersiveIconsTest {

    @Test
    fun the_hero_at_rest_covers_the_bar() = runComposeUiTest {
        setContent { HeroListUnderTest(initialOffsetPx = 0) }

        onNodeWithTag(FLAG_TAG).assertTextEquals("under")
    }

    @Test
    fun the_hero_still_covers_the_bar_just_inside_the_scrim_extent() = runComposeUiTest {
        setContent { HeroListUnderTest(initialOffsetPx = SCRIM_PX - 1) }

        onNodeWithTag(FLAG_TAG).assertTextEquals("under")
    }

    @Test
    fun the_hero_has_left_the_bar_once_it_is_a_full_scrim_extent_down() = runComposeUiTest {
        setContent { HeroListUnderTest(initialOffsetPx = SCRIM_PX) }

        onNodeWithTag(FLAG_TAG).assertTextEquals("over")
    }

    @Test
    fun the_hero_icons_follow_a_swipe_away_and_back() = runComposeUiTest {
        setContent { HeroListUnderTest(initialOffsetPx = 0) }

        onNodeWithTag(LIST_TAG).performTouchInput { swipeUp() }
        waitForIdle()
        onNodeWithTag(FLAG_TAG).assertTextEquals("over")

        onNodeWithTag(LIST_TAG).performTouchInput { swipeDown() }
        waitForIdle()
        onNodeWithTag(FLAG_TAG).assertTextEquals("under")
    }

    @Test
    fun the_still_at_rest_covers_the_bar_and_a_swipe_takes_it_away() = runComposeUiTest {
        setContent { StillUnderTest(initialOffsetPx = 0) }
        onNodeWithTag(FLAG_TAG).assertTextEquals("under")

        onNodeWithTag(LIST_TAG).performTouchInput { swipeUp() }
        waitForIdle()
        onNodeWithTag(FLAG_TAG).assertTextEquals("over")
    }

    @Test
    fun the_still_has_left_the_bar_once_it_is_a_full_scrim_extent_down() = runComposeUiTest {
        setContent { StillUnderTest(initialOffsetPx = SCRIM_PX) }

        onNodeWithTag(FLAG_TAG).assertTextEquals("over")
    }

    @Composable
    private fun HeroListUnderTest(initialOffsetPx: Int) {
        CompositionLocalProvider(LocalDensity provides Density(1f)) {
            val listState = rememberLazyListState(initialFirstVisibleItemScrollOffset = initialOffsetPx)
            val heroUnderBar by rememberHeroUnderBar(listState)
            Box {
                LazyColumn(Modifier.size(300.dp).testTag(LIST_TAG), state = listState) {
                    item { Box(Modifier.fillMaxWidth().height(400.dp)) }
                    items(50) { Box(Modifier.fillMaxWidth().height(60.dp)) }
                }
                Text(if (heroUnderBar) "under" else "over", Modifier.testTag(FLAG_TAG))
            }
        }
    }

    @Composable
    private fun StillUnderTest(initialOffsetPx: Int) {
        CompositionLocalProvider(LocalDensity provides Density(1f)) {
            val scroll = rememberScrollState(initial = initialOffsetPx)
            val stillUnderBar by rememberStillUnderBar(scroll)
            Box {
                Column(Modifier.size(300.dp).verticalScroll(scroll).testTag(LIST_TAG)) {
                    Box(Modifier.fillMaxWidth().height(400.dp))
                    Box(Modifier.fillMaxWidth().height(600.dp))
                }
                Text(if (stillUnderBar) "under" else "over", Modifier.testTag(FLAG_TAG))
            }
        }
    }
}

private const val LIST_TAG = "immersiveIconsList"
private const val FLAG_TAG = "immersiveIconsFlag"

/** [ImmersiveScrimExtent] in px at the pinned density of 1. */
private const val SCRIM_PX = 56
