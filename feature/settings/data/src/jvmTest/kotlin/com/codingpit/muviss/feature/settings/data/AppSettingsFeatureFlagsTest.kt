@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.settings.data

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.flags.TriageControlScheme
import com.codingpit.muviss.core.database.MuvissDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The flag columns against real SQLite, so the defaults that a fresh install
 * gets are the ones the schema actually declares rather than the ones the
 * Kotlin side assumes.
 */
class AppSettingsFeatureFlagsTest {

    private lateinit var flags: AppSettingsFeatureFlags

    @BeforeTest
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MuvissDatabase.Schema.synchronous().create(driver)
        val dispatcher = UnconfinedTestDispatcher()
        flags = AppSettingsFeatureFlags(
            MuvissDatabase(driver).appSettingsQueries,
            object : AppDispatchers {
                override val default: CoroutineDispatcher = dispatcher
                override val io: CoroutineDispatcher = dispatcher
            },
        )
    }

    @Test
    fun motion_is_on_by_default_on_a_fresh_install() = runTest {
        // Both default to on because that is exactly how the app behaved before
        // the toggles existed: nothing changes until the user asks.
        flags.animationsEnabled.test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        flags.triageDeckAnimations.test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setAnimationsEnabled_persists() = runTest {
        flags.setAnimationsEnabled(false)

        flags.animationsEnabled.test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setTriageDeckAnimations_persists() = runTest {
        flags.setTriageDeckAnimations(false)

        flags.triageDeckAnimations.test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun the_two_motion_flags_are_independent_of_each_other_and_of_the_scheme() = runTest {
        flags.setTriageDeckAnimations(false)
        flags.setTriageControlScheme(TriageControlScheme.THREE_WAY)

        // The master is deliberately untouched: turning the deck's own motion
        // off must not flatten the rest of the app.
        flags.animationsEnabled.test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        flags.triageControlScheme.test {
            assertEquals(TriageControlScheme.THREE_WAY, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun automatic_sync_is_off_by_default_on_a_fresh_install() = runTest {
        // Off because turning it on sends a library to a server without being
        // asked each time; the person has to say so (EPIC 40, ADR 0021).
        flags.syncAutomatically.test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setSyncAutomatically_round_trips() = runTest {
        flags.setSyncAutomatically(true)
        assertEquals(true, flags.syncAutomatically.first())

        flags.setSyncAutomatically(false)
        assertEquals(false, flags.syncAutomatically.first())
    }

    @Test
    fun automatic_sync_is_independent_of_the_motion_flags() = runTest {
        flags.setSyncAutomatically(true)

        flags.animationsEnabled.test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        flags.syncAutomatically.test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
