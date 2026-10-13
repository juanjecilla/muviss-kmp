package com.codingpit.muviss.feature.onboarding.domain

import app.cash.turbine.test
import com.codingpit.muviss.feature.onboarding.api.NotificationNudgeResult
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NotificationNudgeTest {

    private class FakeRepository : OnboardingRepository {
        val completed = MutableStateFlow(false)
        val dismissed = MutableStateFlow(false)
        override fun observeCompleted(): Flow<Boolean> = completed

        override suspend fun markCompleted() {
            completed.value = true
        }

        override fun observeNudgeDismissed(): Flow<Boolean> = dismissed

        override suspend fun dismissNudge() {
            dismissed.value = true
        }
    }

    private val show = MediaId.parse("tmdb:tv:1399")
    private val otherShow = MediaId.parse("tmdb:tv:66732")
    private val repository = FakeRepository()
    private val library = MutableStateFlow(listOf(show))
    private val enabled = MutableStateFlow(false)
    private var osAnswer = true
    private var osFailure: Throwable? = null
    private var osWaitsForever = false

    private fun nudge(canNotify: Boolean = true) = NotificationNudge(
        tvShows = library,
        notificationsEnabled = enabled,
        repository = repository,
        canNotify = canNotify,
        turnOn = {
            osFailure?.let { throw it }
            if (osWaitsForever) awaitCancellation()
            osAnswer
        },
    )

    private suspend fun NotificationNudge.eligible(id: MediaId = show): Boolean {
        var result = false
        observe(id).test {
            result = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        return result
    }

    @Test
    fun the_first_saved_tv_show_with_notifications_off_is_offered_the_nudge() = runTest {
        assertEquals(true, nudge().eligible())
    }

    @Test
    fun not_when_notifications_are_already_on() = runTest {
        enabled.value = true
        assertEquals(false, nudge().eligible())
    }

    @Test
    fun not_once_it_has_been_dismissed() = runTest {
        repository.dismissed.value = true
        assertEquals(false, nudge().eligible())
    }

    @Test
    fun not_on_a_platform_that_never_notifies() = runTest {
        assertEquals(false, nudge(canNotify = false).eligible())
    }

    @Test
    fun not_for_the_second_tv_show() = runTest {
        library.value = listOf(show, otherShow)
        assertEquals(false, nudge().eligible(show))
        assertEquals(false, nudge().eligible(otherShow))
    }

    @Test
    fun not_for_a_title_that_is_not_the_one_show_in_the_library() = runTest {
        assertEquals(false, nudge().eligible(otherShow))
    }

    @Test
    fun not_when_no_show_is_saved_yet() = runTest {
        library.value = emptyList()
        assertEquals(false, nudge().eligible())
    }

    @Test
    fun accepting_asks_the_os_and_spends_the_nudge() = runTest {
        assertEquals(NotificationNudgeResult.ENABLED, nudge().accept())
        assertEquals(true, repository.dismissed.value)
    }

    @Test
    fun a_refusal_still_spends_the_nudge_so_it_is_never_offered_twice() = runTest {
        osAnswer = false
        assertEquals(NotificationNudgeResult.DENIED, nudge().accept())
        assertEquals(true, repository.dismissed.value)
        assertEquals(false, nudge().eligible())
    }

    @Test
    fun dismissing_spends_it_too() = runTest {
        nudge().dismiss()
        assertEquals(true, repository.dismissed.value)
    }

    @Test
    fun a_turn_on_that_throws_still_spends_the_nudge() = runTest {
        osFailure = IllegalStateException("no window to prompt over")
        assertFailsWith<IllegalStateException> { nudge().accept() }
        assertEquals(true, repository.dismissed.value)
    }

    @Test
    fun a_cancelled_turn_on_still_spends_the_nudge() = runTest {
        osWaitsForever = true
        val attempt = launch { nudge().accept() }
        runCurrent()
        attempt.cancelAndJoin()
        assertEquals(true, repository.dismissed.value)
    }
}
