@file:OptIn(ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.sync

import com.codingpit.muviss.core.common.remoteconfig.RemoteConfig
import com.codingpit.muviss.core.common.remoteconfig.RemoteValues
import com.codingpit.muviss.core.sync.SyncOutcome
import com.codingpit.muviss.core.testing.FakeSupabaseServer
import com.codingpit.muviss.feature.nextpick.api.NextPickPreferences
import com.codingpit.muviss.feature.nextpick.api.ResultMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * #968: the sync push cycle is a bare `appSettings` `ensureRow()` writer (`SyncEngine.syncCycle`). Like the writers in
 * `NextPickResultModeWritersTest` (#927), it must leave the served Next Pick default on a row it creates, and must not
 * touch a stored choice. This drives a real push cycle through the real engine and the real Supabase backend over
 * [FakeSupabaseServer], which the graph test cannot do without a session.
 */
class SyncPushNextPickDefaultTest {

    private val apps = mutableListOf<SyncApp>()

    @AfterTest
    fun tearDown() = apps.forEach { it.close() }

    private fun TestScope.signedInApp(): SyncApp = SyncApp(
        server = FakeSupabaseServer(),
        dispatcher = UnconfinedTestDispatcher(testScheduler),
        clock = LambdaClock { CLOCK_BASE + testScheduler.currentTime },
        remoteConfig = ServedRemoteConfig(served = "TOP_PICKS"),
    ).also { apps += it }

    private fun SyncApp.settingsRow() = database.appSettingsQueries.selectSettings().executeAsOneOrNull()

    private suspend fun SyncApp.resultMode(): ResultMode = koin.get<NextPickPreferences>().resultMode.first()

    @Test
    fun a_push_cycle_that_creates_the_settings_row_leaves_the_served_default() = runTest {
        val app = signedInApp()
        app.signIn()
        // The graph may have created the row already at startup; the push must be the writer that creates it.
        app.database.appSettingsQueries.deleteRow()
        assertNull(app.settingsRow(), "no row before the push cycle")

        val outcome = app.engine.syncNow()

        assertIs<SyncOutcome.Success>(outcome)
        assertNotNull(app.settingsRow(), "the push cycle creates the row")
        assertNull(app.settingsRow()?.nextPickResultMode, "the row the push created has no stored result mode")
        assertEquals(ResultMode.TOP_PICKS, app.resultMode(), "the push-created row takes the served default")
    }

    @Test
    fun a_stored_choice_is_left_unchanged_by_the_push_cycle() = runTest {
        val app = signedInApp()
        app.signIn()
        app.database.appSettingsQueries.deleteRow()
        assertIs<SyncOutcome.Success>(app.engine.syncNow())
        app.koin.get<NextPickPreferences>().setResultMode(ResultMode.DECK)

        val outcome = app.engine.syncNow()

        assertIs<SyncOutcome.Success>(outcome)
        assertEquals(ResultMode.DECK, app.resultMode(), "a choice stored before the cycle survives it")
    }
}

/** Serves one value from the stored copy, as the Remote Config seam does once a fetch has stored it. */
private class ServedRemoteConfig(served: String) : RemoteConfig {
    private val stored = RemoteValues(byokEnabled = false, nextPickDefaultResultMode = served)

    override val values: StateFlow<RemoteValues> = MutableStateFlow(stored)

    override suspend fun awaitStored(): RemoteValues = stored

    override suspend fun refreshIfStale() = Unit
}

private const val CLOCK_BASE = 1_800_000_000_000L
