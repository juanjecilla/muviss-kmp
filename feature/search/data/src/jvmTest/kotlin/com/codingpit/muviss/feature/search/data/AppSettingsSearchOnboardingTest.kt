package com.codingpit.muviss.feature.search.data

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.MuvissDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppSettingsSearchOnboardingTest {

    private class TestDispatchers(d: CoroutineDispatcher) : AppDispatchers {
        override val io: CoroutineDispatcher = d
        override val default: CoroutineDispatcher = d
    }

    private fun onboarding(dispatcher: CoroutineDispatcher): AppSettingsSearchOnboarding {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MuvissDatabase.Schema.synchronous().create(driver)
        return AppSettingsSearchOnboarding(MuvissDatabase(driver).appSettingsQueries, TestDispatchers(dispatcher))
    }

    @Test
    fun `a fresh install has not seen the intro, and dismissing it sticks`() = runTest {
        val store = onboarding(StandardTestDispatcher(testScheduler))

        assertFalse(store.observeIntroSeen().first())
        store.setIntroSeen()
        assertTrue(store.observeIntroSeen().first())
    }
}
