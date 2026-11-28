package com.codingpit.muviss.feature.search.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import kotlin.test.Test

/** The start destination a malformed detail link must return to (a stand-in for whatever the launch landed on). */
@Serializable
internal data object LaunchTarget

private const val LAUNCH_TAG = "launchTarget"

/**
 * A `muviss://title/1396` link, or a stale notification, carries an id with no `source:type` prefix. [DetailRoute]
 * must pop back to where the launch landed, not throw during composition: a throw there aborts the app on iOS (#863).
 * The guard is `runCatching { MediaId.parse(...) }` in [searchSection].
 */
@OptIn(ExperimentalTestApi::class)
class DetailRouteMalformedIdTest {

    @Test
    fun a_malformed_detail_id_pops_back_to_the_launch_destination() = runComposeUiTest {
        lateinit var navigation: NavController
        setContent {
            val navController = rememberNavController()
            navigation = navController
            NavHost(navController, startDestination = LaunchTarget) {
                composable<LaunchTarget> { Box(Modifier.testTag(LAUNCH_TAG)) { Text("launch") } }
                searchSection(
                    navController = navController,
                    onOpenTriage = {},
                    onOpenNextPick = {},
                    linkLanguage = { "en-US" },
                )
            }
        }
        runOnIdle { navigation.navigate(DetailRoute("1396")) }
        waitForIdle()

        onNodeWithTag(LAUNCH_TAG).assertIsDisplayed()
    }
}
