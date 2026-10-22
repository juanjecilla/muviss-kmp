package com.codingpit.muviss.feature.onboarding.data

import app.cash.turbine.test
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.testing.inMemoryDatabase
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** EPIC 47: a fresh install's row starts on the introduction, and the flags are per-device facts the repository can flip. */
class SqlDelightOnboardingRepositoryTest {

    private val database = inMemoryDatabase()
    private val dispatcher = UnconfinedTestDispatcher()
    private val repository = SqlDelightOnboardingRepository(
        database.appSettingsQueries,
        object : AppDispatchers {
            override val default = dispatcher
            override val io = dispatcher
        },
    )

    @Test
    fun a_fresh_install_has_not_completed_the_introduction() = runTest {
        repository.observeCompleted().test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun marking_it_completed_is_observed_and_kept() = runTest {
        repository.observeCompleted().test {
            assertEquals(false, awaitItem())
            repository.markCompleted()
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun the_nudge_starts_undismissed_and_dismissing_sticks() = runTest {
        repository.observeNudgeDismissed().test {
            assertEquals(false, awaitItem())
            repository.dismissNudge()
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun the_two_flags_are_independent() = runTest {
        repository.markCompleted()
        repository.observeNudgeDismissed().test {
            assertEquals(false, awaitItem(), "completing the introduction does not spend the nudge")
            cancelAndIgnoreRemainingEvents()
        }
    }
}
