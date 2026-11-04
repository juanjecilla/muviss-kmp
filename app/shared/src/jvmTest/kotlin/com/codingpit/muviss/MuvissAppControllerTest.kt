package com.codingpit.muviss

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigation.compose.rememberNavController
import kotlin.test.Test
import kotlin.test.assertNull

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
}
