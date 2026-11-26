@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Two taps on the same artwork before the first viewer is on screen must open one viewer, not two: Back from the
 * viewer would otherwise show the viewer again before it reaches Detail.
 */
class ImageViewerNavigationTest {

    @Test
    fun a_second_tap_on_the_same_artwork_does_not_push_a_second_viewer() = runComposeUiTest {
        lateinit var nav: NavHostController
        setContent { nav = TestNavHost() }
        waitForIdle()
        val route = ImageViewerRoute(mediaId = "1", kind = "Poster", seedUrl = "https://image.tmdb.org/t/p/w500/p.jpg")

        nav.openImageViewer(route)
        nav.openImageViewer(route)
        waitForIdle()

        val viewers = nav.currentBackStack.value.count { it.destination.route?.startsWith(ImageViewerRoute::class.qualifiedName!!) == true }
        assertEquals(1, viewers)
    }

    @Composable
    private fun TestNavHost(): NavHostController {
        val nav = rememberNavController()
        NavHost(nav, startDestination = DetailRoute("1")) {
            composable<DetailRoute> {}
            composable<ImageViewerRoute> {}
        }
        return nav
    }
}
