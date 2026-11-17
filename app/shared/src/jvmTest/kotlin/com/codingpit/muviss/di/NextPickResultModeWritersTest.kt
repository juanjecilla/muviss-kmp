package com.codingpit.muviss.di

import com.codingpit.muviss.core.common.remoteconfig.RemoteConfig
import com.codingpit.muviss.core.common.remoteconfig.RemoteValues
import com.codingpit.muviss.core.common.secrets.InMemorySecretStore
import com.codingpit.muviss.core.common.secrets.SecretStore
import com.codingpit.muviss.core.database.DatabaseDriverFactory
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.feature.cowatch.api.CoWatchApi
import com.codingpit.muviss.feature.cowatch.api.PoolSource
import com.codingpit.muviss.feature.nextpick.api.NextPickPreferences
import com.codingpit.muviss.feature.nextpick.api.ResultMode
import com.codingpit.muviss.feature.nextpick.domain.CompletionPromptStore
import com.codingpit.muviss.feature.onboarding.data.ApplySystemMotionDefault
import com.codingpit.muviss.feature.onboarding.domain.OnboardingRepository
import com.codingpit.muviss.feature.search.domain.SearchOnboarding
import com.codingpit.muviss.feature.settings.domain.BackupRestorer
import com.codingpit.muviss.feature.triage.domain.TriagePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * #927: every bare `ensureRow()` writer of `appSettings` creates the same unchosen row, so the served Next Pick default
 * still reaches it. Each test starts from a graph with no settings row, runs one writer through its own path on the real
 * graph and the real database, and reads `NextPickPreferences.resultMode` with a served default of TOP_PICKS. The second
 * half of each test makes a choice first and checks that the same write leaves it alone.
 *
 * Run from `:app:shared`, the only module that sees every writer and the reader together. Not covered here: the profile
 * writers (their `ensureRow` is the profile singleton, not `appSettings`) and the sync push cycle (needs an authenticated
 * backend; see the issue filed with #927).
 */
class NextPickResultModeWritersTest {

    private var koin: Koin? = null

    @AfterTest
    fun tearDown() {
        koin?.let { stopKoin() }
        koin = null
    }

    private fun startGraph(served: String): Koin {
        val directory = createTempDirectory("muviss-next-pick-writers").toFile()
        val app = startKoin {
            modules(
                appModules + module {
                    single { DatabaseDriverFactory(directory) }
                    single<SecretStore> { InMemorySecretStore() }
                    single<RemoteConfig> { ServedRemoteConfig(served) }
                },
            )
        }
        koin = app.koin
        return app.koin
    }

    private fun Koin.settingsRow() = get<MuvissDatabase>().appSettingsQueries.selectSettings().executeAsOneOrNull()

    private suspend fun Koin.resultMode(): ResultMode = get<NextPickPreferences>().resultMode.first()

    /**
     * Starts with no row, runs [write], and expects the served default with the choice still unwritten. Then restarts,
     * makes a stored choice, runs [write] again, and expects that choice to win.
     */
    private suspend fun assertWriterLeavesServedDefault(write: suspend (Koin) -> Unit) {
        val fresh = startGraph(served = "TOP_PICKS")
        assertNull(fresh.settingsRow(), "the graph starts with no appSettings row")
        write(fresh)
        assertEquals(ResultMode.TOP_PICKS, fresh.resultMode(), "a row this writer created takes the served default")
        assertNull(fresh.settingsRow()?.nextPickResultMode, "the default is served, not written into the row")
        stopKoin()
        koin = null

        val chosen = startGraph(served = "TOP_PICKS")
        chosen.get<NextPickPreferences>().setResultMode(ResultMode.DECK)
        write(chosen)
        assertEquals(ResultMode.DECK, chosen.resultMode(), "a stored choice made before the write is left unchanged")
    }

    @Test
    fun triage_tutorial_seen_writer_leaves_the_served_default() = runTest {
        assertWriterLeavesServedDefault { it.get<TriagePreferences>().setTutorialSeen(true) }
    }

    @Test
    fun triage_snooze_hint_writer_leaves_the_served_default() = runTest {
        assertWriterLeavesServedDefault { it.get<TriagePreferences>().setSnoozeHintSeen(true) }
    }

    @Test
    fun search_intro_writer_leaves_the_served_default() = runTest {
        assertWriterLeavesServedDefault { it.get<SearchOnboarding>().setIntroSeen() }
    }

    @Test
    fun onboarding_mark_completed_writer_leaves_the_served_default() = runTest {
        assertWriterLeavesServedDefault { it.get<OnboardingRepository>().markCompleted() }
    }

    @Test
    fun onboarding_dismiss_nudge_writer_leaves_the_served_default() = runTest {
        assertWriterLeavesServedDefault { it.get<OnboardingRepository>().dismissNudge() }
    }

    @Test
    fun onboarding_system_motion_default_writer_leaves_the_served_default() = runTest {
        assertWriterLeavesServedDefault { it.get<ApplySystemMotionDefault>().invoke() }
    }

    @Test
    fun next_pick_completion_prompt_writer_leaves_the_served_default() = runTest {
        assertWriterLeavesServedDefault { it.get<CompletionPromptStore>().markShown(atEpochMs = 1_000L) }
    }

    @Test
    fun co_watch_pool_source_writer_leaves_the_served_default() = runTest {
        assertWriterLeavesServedDefault { it.get<CoWatchApi>().setPoolSource(PoolSource.Named("watchlist")) }
    }

    @Test
    fun settings_restore_writer_leaves_the_served_default() = runTest {
        val backup = """
            {
              "exportedAtEpochMs": 1759000000000,
              "collection": [],
              "progress": [],
              "settings": {
                "theme": "SYSTEM",
                "language": "en",
                "region": "US",
                "animationsEnabled": true,
                "triageDeckAnimations": true,
                "tickAnimations": true,
                "triageControlScheme": "TAP",
                "triageSnoozePeriod": "WEEK",
                "triageSnoozePlacement": "END",
                "mutedGenres": "",
                "mutedGenresInSearch": false,
                "displayPreferencesUpdatedAtEpochMs": 1
              }
            }
        """.trimIndent()
        assertWriterLeavesServedDefault { it.get<BackupRestorer>().restore(backup) }
    }
}

/** Serves one value from the stored copy, as the Remote Config seam does once a fetch has stored it. */
private class ServedRemoteConfig(servedMode: String) : RemoteConfig {
    private val served = RemoteValues(byokEnabled = false, nextPickDefaultResultMode = servedMode)

    override val values: StateFlow<RemoteValues> = MutableStateFlow(served)

    override suspend fun awaitStored(): RemoteValues = served

    override suspend fun refreshIfStale() = Unit
}
