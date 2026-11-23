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
 * The one resolver for `appSettings.nextPickResultMode` (#853, #820), against real SQLite.
 *
 * Three states, each read through [AppSettingsNextPickPreferences.resultMode]: never chose (NULL), which takes the served
 * Remote Config default, else One Pick; chose One Pick; chose Deck. A stored choice wins over whatever is served, and a
 * stored choice never reads Remote Config at all. Every `ensureRow` caller creates the same NULL row, so the cases below
 * run from `ensureRow` rather than from one writer.
 */
class NextPickResultModeResolverTest {

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

    private fun preferences(remote: RemoteConfig) = AppSettingsNextPickPreferences(queries, dispatchers, remote)

    private fun storedMode(): String? = queries.selectSettings().executeAsOne().nextPickResultMode

    @Test
    fun a_fresh_row_is_never_chose() = runTest {
        queries.ensureRow()

        assertEquals(null, storedMode())
    }

    @Test
    fun never_chose_takes_the_served_top_picks_default() = runTest {
        queries.ensureRow()

        assertEquals(ResultMode.TOP_PICKS, preferences(CountingRemoteConfig("TOP_PICKS")).resultMode.first())
    }

    @Test
    fun never_chose_takes_a_served_deck_now_that_deck_is_offered() = runTest {
        queries.ensureRow()

        assertEquals(ResultMode.DECK, preferences(CountingRemoteConfig("DECK")).resultMode.first())
    }

    @Test
    fun never_chose_reads_the_served_text_without_regard_to_case_or_spaces() = runTest {
        queries.ensureRow()

        assertEquals(ResultMode.TOP_PICKS, preferences(CountingRemoteConfig(" top_picks ")).resultMode.first())
    }

    @Test
    fun never_chose_with_nothing_recognised_served_is_one_pick() = runTest {
        queries.ensureRow()

        assertEquals(ResultMode.ONE_PICK, preferences(CountingRemoteConfig("carousel")).resultMode.first())
    }

    @Test
    fun never_chose_stays_unstored_after_being_read() = runTest {
        queries.ensureRow()

        preferences(CountingRemoteConfig("TOP_PICKS")).resultMode.first()

        assertEquals(null, storedMode(), "a read resolves the default but never writes it")
    }

    @Test
    fun chose_one_pick_stays_one_pick_when_top_picks_is_served() = runTest {
        queries.ensureRow()
        preferences(CountingRemoteConfig("ONE_PICK")).setResultMode(ResultMode.ONE_PICK)

        assertEquals(ResultMode.ONE_PICK, preferences(CountingRemoteConfig("TOP_PICKS")).resultMode.first())
    }

    @Test
    fun chose_deck_stays_deck_when_one_pick_is_served() = runTest {
        queries.ensureRow()
        preferences(CountingRemoteConfig("ONE_PICK")).setResultMode(ResultMode.DECK)

        assertEquals(ResultMode.DECK, preferences(CountingRemoteConfig("ONE_PICK")).resultMode.first())
    }

    @Test
    fun a_stored_choice_never_consults_remote_config() = runTest {
        queries.ensureRow()
        queries.updateNextPickResultMode(ResultMode.TOP_PICKS.name)
        val remote = CountingRemoteConfig("ONE_PICK")

        preferences(remote).resultMode.first()
        preferences(remote).resultMode.first()

        assertEquals(0, remote.storedReads)
    }

    @Test
    fun never_chose_consults_remote_config_once_per_read() = runTest {
        queries.ensureRow()
        val remote = CountingRemoteConfig("TOP_PICKS")

        preferences(remote).resultMode.first()

        assertEquals(1, remote.storedReads)
    }

    @Test
    fun a_reader_that_creates_the_row_first_still_resolves_the_served_default() = runTest {
        // The session and Settings read through ensureRow (onStart), so the first reader can be a reader, not a writer.
        assertEquals(ResultMode.TOP_PICKS, preferences(CountingRemoteConfig("TOP_PICKS")).resultMode.first())
        assertEquals(null, storedMode())
    }
}

/** Serves one value and counts how often the stored copy was read, so a test can see whether a read consulted it. */
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
