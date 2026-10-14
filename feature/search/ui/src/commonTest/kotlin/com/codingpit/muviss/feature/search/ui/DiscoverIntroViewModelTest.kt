@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import com.codingpit.muviss.feature.search.domain.SearchOnboarding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class FakeSearchOnboarding(seen: Boolean) : SearchOnboarding {
    val seen = MutableStateFlow(seen)
    override fun observeIntroSeen(): Flow<Boolean> = seen
    override suspend fun setIntroSeen() {
        seen.value = true
    }
}

class DiscoverIntroViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun a_new_install_sees_the_intro_until_it_is_dismissed() = runTest {
        val onboarding = FakeSearchOnboarding(seen = false)
        val vm = DiscoverIntroViewModel(onboarding)
        advanceUntilIdle()
        assertTrue(vm.visible.value)

        vm.dismiss()
        advanceUntilIdle()

        assertFalse(vm.visible.value)
        assertTrue(onboarding.seen.value)
    }

    @Test
    fun someone_who_has_seen_it_never_does_again() = runTest {
        val vm = DiscoverIntroViewModel(FakeSearchOnboarding(seen = true))
        advanceUntilIdle()

        assertFalse(vm.visible.value)
    }
}
