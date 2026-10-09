package com.codingpit.muviss.feature.search.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.testing.inMemoryDatabase
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

    private fun onboarding(dispatcher: CoroutineDispatcher): AppSettingsSearchOnboarding = AppSettingsSearchOnboarding(inMemoryDatabase().appSettingsQueries, TestDispatchers(dispatcher))

    @Test
    fun `a fresh install has not seen the intro, and dismissing it sticks`() = runTest {
        val store = onboarding(StandardTestDispatcher(testScheduler))

        assertFalse(store.observeIntroSeen().first())
        store.setIntroSeen()
        assertTrue(store.observeIntroSeen().first())
    }
}
