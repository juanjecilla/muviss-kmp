package com.codingpit.muviss

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.codingpit.muviss.feature.nextpick.ui.NextPickRoute
import com.codingpit.muviss.feature.search.ui.SearchRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MuvissAppControllerTest {

    @Test
    fun `the menu does nothing before the NavHost has a graph instead of throwing`() = runComposeUiTest {
        // Given a controller whose NavHost has not composed yet, as while MuvissApp reads the start route
        lateinit var controller: MuvissAppController
        setContent { controller = MuvissAppController(rememberNavController()) }

        // When the desktop menu asks for What's next? and for a tab
        runOnIdle {
            controller.openNextPick()
            controller.navigateTo(controller.destinations.first())
        }

        // Then nothing navigated, and nothing threw
        assertNull(controller.navController.currentBackStackEntry)
    }

    @Test
    fun `the menu's What's next opens the Pick Session with no anchor`() = runComposeUiTest {
        // Given a controller over a graph that holds the Pick Session
        lateinit var controller: MuvissAppController
        setContent {
            val navController = rememberNavController()
            controller = MuvissAppController(navController)
            NavHost(navController, startDestination = SearchRoute) {
                composable<SearchRoute> {}
                composable<NextPickRoute> {}
            }
        }

        // When the desktop menu asks for What's next?
        runOnIdle { controller.openNextPick() }

        // Then the session is open, and the menu passed no title it was opened from
        runOnIdle {
            val entry = checkNotNull(controller.navController.currentBackStackEntry)
            assertTrue(entry.destination.hasRoute<NextPickRoute>())
            assertEquals(null, entry.toRoute<NextPickRoute>().anchor)
        }
    }
}
