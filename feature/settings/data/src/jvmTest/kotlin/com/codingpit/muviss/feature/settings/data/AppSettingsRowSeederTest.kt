@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.settings.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.remoteconfig.RemoteConfig
import com.codingpit.muviss.core.common.remoteconfig.RemoteValues
import com.codingpit.muviss.core.database.AppSettingsQueries
import com.codingpit.muviss.core.testing.inMemoryDatabase
import com.codingpit.muviss.feature.nextpick.api.ResultMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Next Pick result mode a fresh `appSettings` row starts on (EPIC 51, #556), against real SQLite.
 *
 * The owner's decision is that Remote Config seeds a row only when it creates one, and never changes a row that exists.
 * So these pin three things: a fresh row takes what was served (with Deck refused, as it is not offered yet); a row that
 * exists keeps its value whatever is served; and the common path does not consult Remote Config at all.
 */
class AppSettingsRowSeederTest {

    private lateinit var queries: AppSettingsQueries
    private lateinit var dispatchers: AppDispatchers

    @BeforeTest
    fun setUp() {
        val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()
        dispatchers = object : AppDispatchers {
            override val default: CoroutineDispatcher = dispatcher
            override val io: CoroutineDispatcher = dispatcher
        }
        queries = inMemoryDatabase().appSettingsQueries
    }

    private fun seeder(remote: RemoteConfig) = AppSettingsRowSeeder(queries, remote, dispatchers)

    private fun storedMode(): String = queries.selectSettings().executeAsOne().nextPickResultMode

    @Test
    fun a_fresh_row_starts_on_the_served_mode() = runTest {
        val remote = CountingRemoteConfig(servedMode = "TOP_PICKS")

        seeder(remote).ensureRow()

        assertEquals("TOP_PICKS", storedMode())
    }

    @Test
    fun a_served_mode_is_read_without_regard_to_case_or_spaces() = runTest {
        val remote = CountingRemoteConfig(servedMode = "  top_picks ")

        seeder(remote).ensureRow()

        assertEquals("TOP_PICKS", storedMode())
    }

    @Test
    fun a_served_deck_seeds_one_pick_because_deck_is_not_offered_yet() = runTest {
        val remote = CountingRemoteConfig(servedMode = "DECK")

        seeder(remote).ensureRow()

        assertEquals(ResultMode.DEFAULT.name, storedMode())
    }

    @Test
    fun unknown_served_text_seeds_one_pick() = runTest {
        val remote = CountingRemoteConfig(servedMode = "carousel")

        seeder(remote).ensureRow()

        assertEquals(ResultMode.DEFAULT.name, storedMode())
    }

    @Test
    fun a_row_that_is_already_one_pick_stays_one_pick_when_top_picks_is_served() = runTest {
        seeder(CountingRemoteConfig(servedMode = "ONE_PICK")).ensureRow()
        queries.updateNextPickResultMode(ResultMode.ONE_PICK.name)
        val remote = CountingRemoteConfig(servedMode = "TOP_PICKS")

        seeder(remote).ensureRow()

        assertEquals("ONE_PICK", storedMode())
    }

    @Test
    fun a_row_set_to_deck_stays_deck_when_top_picks_is_served() = runTest {
        seeder(CountingRemoteConfig(servedMode = "ONE_PICK")).ensureRow()
        queries.updateNextPickResultMode(ResultMode.DECK.name)

        seeder(CountingRemoteConfig(servedMode = "TOP_PICKS")).ensureRow()

        assertEquals("DECK", storedMode())
    }

    @Test
    fun an_existing_row_does_not_consult_remote_config() = runTest {
        seeder(CountingRemoteConfig(servedMode = "ONE_PICK")).ensureRow()
        val remote = CountingRemoteConfig(servedMode = "TOP_PICKS")

        seeder(remote).ensureRow()
        seeder(remote).ensureRow()

        assertEquals(0, remote.storedReads)
    }

    @Test
    fun a_fresh_row_consults_remote_config_once() = runTest {
        val remote = CountingRemoteConfig(servedMode = "TOP_PICKS")

        seeder(remote).ensureRow()

        assertEquals(1, remote.storedReads)
    }

    @Test
    fun the_preferences_see_the_seeded_mode_on_a_fresh_install() = runTest {
        val preferences = AppSettingsNextPickPreferences(queries, dispatchers, CountingRemoteConfig(servedMode = "TOP_PICKS"))

        assertEquals(ResultMode.TOP_PICKS, preferences.resultMode.first())
    }
}

/** Serves one value and counts how often the stored copy was read, so a test can see whether a row consulted it. */
private class CountingRemoteConfig(servedMode: String) : RemoteConfig {
    private val served = RemoteValues(byokEnabled = false, nextPickDefaultResultMode = servedMode)
    var storedReads = 0
        private set

    override val values: StateFlow<RemoteValues> = MutableStateFlow(served)

    override suspend fun awaitStored(): RemoteValues {
        storedReads++
        return served
    }

    override suspend fun refreshIfStale() = Unit
}
