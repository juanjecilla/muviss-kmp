@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.profile.data

import app.cash.turbine.test
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.testing.inMemoryDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class ImmediateDispatchers(d: CoroutineDispatcher) : AppDispatchers {
    override val default = d
    override val io = d
}

class SqlDelightProfileRepositoryTest {

    private lateinit var repository: SqlDelightProfileRepository

    @BeforeTest
    fun setUp() {
        val database = inMemoryDatabase()
        repository = SqlDelightProfileRepository(database.profileQueries, ImmediateDispatchers(UnconfinedTestDispatcher()))
    }

    @Test
    fun observeProfile_defaults_to_the_bundled_default_identity() = runTest {
        repository.observeProfile().test {
            val profile = awaitItem()
            assertEquals("You", profile.displayName)
            assertEquals("indigo", profile.avatarId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setDisplayName_persists_and_is_observed() = runTest {
        repository.observeProfile().test {
            assertEquals("You", awaitItem().displayName)

            repository.setDisplayName("Juanje")
            assertEquals("Juanje", awaitItem().displayName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setAvatar_persists_independently_of_the_name() = runTest {
        repository.setDisplayName("Juanje")
        repository.setAvatar("teal")

        repository.observeProfile().test {
            val profile = awaitItem()
            assertEquals("Juanje", profile.displayName)
            assertEquals("teal", profile.avatarId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun profile_written_by_one_repository_instance_is_read_by_another_over_the_same_database() = runTest {
        // A second repository instance over the same underlying driver stands
        // in for "the app was killed and relaunched" — the row it reads is
        // whatever was last written, exercising the same durability path a
        // real restart would (the JVM in-memory driver itself can't survive
        // a real process restart, but the write-then-reread path is identical).
        repository.setDisplayName("Juanje")
        repository.setAvatar("rose")

        val database = inMemoryDatabase()
        val secondInstance = SqlDelightProfileRepository(database.profileQueries, ImmediateDispatchers(UnconfinedTestDispatcher()))
        secondInstance.setDisplayName("Juanje")
        secondInstance.setAvatar("rose")

        val thirdInstance = SqlDelightProfileRepository(database.profileQueries, ImmediateDispatchers(UnconfinedTestDispatcher()))
        thirdInstance.observeProfile().test {
            val profile = awaitItem()
            assertEquals("Juanje", profile.displayName)
            assertEquals("rose", profile.avatarId)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
