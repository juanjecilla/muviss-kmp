@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.triage.ui

import com.codingpit.muviss.core.common.flags.TriageControlScheme
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.feature.triage.domain.TriageEvent
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TriageViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val filmA = movie("1")
    private val filmB = movie("2")
    private val showA = show("10")

    @Test
    fun `loads a deck on init and puts the first card on top`() = runTest {
        val vm = TriageHarness(movies = listOf(filmA, filmB)).viewModel()
        advanceUntilIdle()

        assertEquals(filmA.id, vm.state.value.topCard?.id)
        assertEquals(filmB.id, vm.state.value.peekedCard?.id)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `a swipe advances the card before its side effects have landed`() = runTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        val gate = CompletableDeferred<Unit>()
        harness.details.gate = gate
        val vm = harness.viewModel()
        advanceUntilIdle()

        vm.onDecide(TriageVerdict.LATER, viaGesture = true)
        advanceUntilIdle()

        // The optimistic-commit promise: the deck never waits on a details()
        // call, which for TV is several sequential requests.
        assertEquals(filmB.id, vm.state.value.topCard?.id)
        assertEquals(emptyList(), harness.collection.added)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(filmA.id), harness.collection.added)
    }

    @Test
    fun `the decision is recorded even while the side effects are still pending`() = runTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        harness.details.gate = CompletableDeferred()
        val vm = harness.viewModel()
        advanceUntilIdle()

        vm.onDecide(TriageVerdict.CAUGHT_UP, viaGesture = true)
        advanceUntilIdle()

        // Dedupe must not depend on the network.
        assertEquals(TriageVerdict.CAUGHT_UP, harness.repository.decisions.value[filmA.id]?.verdict)
    }

    @Test
    fun `a failed commit surfaces a retryable error and keeps the decision`() = runTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        harness.details.failure = IllegalStateException("offline")
        val vm = harness.viewModel()
        advanceUntilIdle()

        vm.onDecide(TriageVerdict.LATER, viaGesture = false)
        advanceUntilIdle()

        val failed = assertNotNull(vm.state.value.failedCommit)
        assertEquals(filmA.id, failed.summary.id)
        assertEquals("offline", failed.message)
        assertFalse(harness.repository.decisions.value.getValue(filmA.id).resolved)
    }

    @Test
    fun `retrying a failed commit completes it`() = runTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        harness.details.failure = IllegalStateException("offline")
        val vm = harness.viewModel()
        advanceUntilIdle()
        vm.onDecide(TriageVerdict.LATER, viaGesture = false)
        advanceUntilIdle()

        harness.details.failure = null
        vm.onRetryFailedCommit()
        advanceUntilIdle()

        assertNull(vm.state.value.failedCommit)
        assertEquals(listOf(filmA.id), harness.collection.added)
    }

    @Test
    fun `undo puts the card back on top and reverses the verdict`() = runTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        val vm = harness.viewModel()
        advanceUntilIdle()
        vm.onDecide(TriageVerdict.CAUGHT_UP, viaGesture = true)
        advanceUntilIdle()

        vm.onUndo()
        advanceUntilIdle()

        assertEquals(filmA.id, vm.state.value.topCard?.id)
        assertEquals(emptyMap(), harness.repository.decisions.value)
        assertEquals(listOf(filmA.id), harness.collection.removed)
        assertEquals(listOf(filmA.id), harness.progress.cleared)
        assertNull(vm.state.value.undoable)
    }

    @Test
    fun `an undone card can be swiped again rather than being permanently skipped over`() = runTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        val vm = harness.viewModel()
        advanceUntilIdle()
        vm.onDecide(TriageVerdict.SKIP, viaGesture = true)
        advanceUntilIdle()
        vm.onUndo()
        advanceUntilIdle()

        vm.onDecide(TriageVerdict.LATER, viaGesture = true)
        advanceUntilIdle()

        assertEquals(TriageVerdict.LATER, harness.repository.decisions.value[filmA.id]?.verdict)
    }

    @Test
    fun `Watching is never offered for a movie`() = runTest {
        val vm = TriageHarness(movies = listOf(filmA)).viewModel()
        advanceUntilIdle()

        assertEquals(listOf(TriageVerdict.SKIP, TriageVerdict.LATER, TriageVerdict.CAUGHT_UP), vm.state.value.verdictsForTopCard)
    }

    @Test
    fun `deciding Watching on a movie is ignored rather than crashing`() = runTest {
        val harness = TriageHarness(movies = listOf(filmA))
        val vm = harness.viewModel()
        advanceUntilIdle()

        vm.onDecide(TriageVerdict.WATCHING, viaGesture = true)
        advanceUntilIdle()

        assertEquals(filmA.id, vm.state.value.topCard?.id)
        assertEquals(emptyMap(), harness.repository.decisions.value)
    }

    @Test
    fun `all four verdicts are offered for a show`() = runTest {
        val vm = TriageHarness(tv = listOf(showA)).viewModel()
        advanceUntilIdle()

        assertEquals(TriageVerdict.entries, vm.state.value.verdictsForTopCard)
    }

    @Test
    fun `the control scheme flag reaches the state`() = runTest {
        val harness = TriageHarness(movies = listOf(filmA), scheme = TriageControlScheme.THREE_WAY)
        val vm = harness.viewModel()
        advanceUntilIdle()

        assertEquals(TriageControlScheme.THREE_WAY, vm.state.value.controlScheme)

        harness.flags.setTriageControlScheme(TriageControlScheme.FOUR_WAY)
        advanceUntilIdle()
        assertEquals(TriageControlScheme.FOUR_WAY, vm.state.value.controlScheme)
    }

    @Test
    fun `the tutorial shows on a fresh device and not once dismissed`() = runTest {
        val harness = TriageHarness(movies = listOf(filmA), tutorialSeen = false)
        val vm = harness.viewModel()
        advanceUntilIdle()

        assertTrue(vm.state.value.tutorialVisible)

        vm.onTutorialDismissed()
        advanceUntilIdle()

        assertFalse(vm.state.value.tutorialVisible)
        assertTrue(harness.preferences.tutorialSeen.value)
        // And it does not pop straight back up as the preference re-emits.
        assertFalse(harness.viewModel().state.value.tutorialVisible)
    }

    @Test
    fun `the tutorial can be reopened deliberately`() = runTest {
        val vm = TriageHarness(movies = listOf(filmA)).viewModel()
        advanceUntilIdle()
        assertFalse(vm.state.value.tutorialVisible)

        vm.onShowTutorial()

        assertTrue(vm.state.value.tutorialVisible)
    }

    @Test
    fun `changing the type filter reloads the deck and drops the genre`() = runTest {
        val harness = TriageHarness(movies = listOf(filmA), tv = listOf(showA), genres = listOf(Genre("18", "Drama")))
        val vm = harness.viewModel()
        advanceUntilIdle()
        vm.onGenreFilterChange("18")
        advanceUntilIdle()

        vm.onTypeFilterChange(MediaType.TV)
        advanceUntilIdle()

        // Genre ids are per-catalogue, so carrying one across types would filter on nonsense.
        assertNull(vm.state.value.filter.genreId)
        assertEquals(showA.id, vm.state.value.topCard?.id)
    }

    @Test
    fun `genre chips only appear once a type is chosen`() = runTest {
        val vm = TriageHarness(movies = listOf(filmA), genres = listOf(Genre("18", "Drama"))).viewModel()
        advanceUntilIdle()

        assertEquals(emptyList(), vm.state.value.genresForFilter)

        vm.onTypeFilterChange(MediaType.MOVIE)
        advanceUntilIdle()
        assertEquals(listOf(Genre("18", "Drama")), vm.state.value.genresForFilter)
    }

    @Test
    fun `an exhausted source leaves an empty deck rather than an error`() = runTest {
        val vm = TriageHarness().viewModel()
        advanceUntilIdle()

        assertNull(vm.state.value.topCard)
        assertTrue(vm.state.value.exhausted)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `a load failure surfaces as a retryable error`() = runTest {
        val harness = TriageHarness(movies = listOf(filmA))
        harness.source.failure = IllegalStateException("no network")
        val vm = harness.viewModel()
        advanceUntilIdle()

        assertEquals("no network", vm.state.value.error)

        harness.source.failure = null
        vm.retry()
        advanceUntilIdle()
        assertEquals(filmA.id, vm.state.value.topCard?.id)
    }

    @Test
    fun `titles already decided or collected never enter the deck`() = runTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        harness.repository.record(
            com.codingpit.muviss.feature.triage.domain.TriageDecision.of(filmA, TriageVerdict.SKIP, NOW_EPOCH_MS, resolved = true),
        )
        val vm = harness.viewModel()
        advanceUntilIdle()

        assertEquals(listOf(filmB.id), vm.state.value.cards.map { it.id })
    }

    @Test
    fun `every decision is reported to analytics with how it was made`() = runTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        val vm = harness.viewModel()
        advanceUntilIdle()

        vm.onDecide(TriageVerdict.SKIP, viaGesture = true)
        advanceUntilIdle()
        vm.onUndo()
        advanceUntilIdle()

        // Nothing is listening today (the tracker is a no-op), but the
        // gesture-vs-button split is the signal the two schemes would be
        // compared on, so it is recorded from the first commit.
        val recorded = harness.analytics.events.filterIsInstance<TriageEvent.DecisionRecorded>().single()
        assertEquals(TriageVerdict.SKIP, recorded.verdict)
        assertTrue(recorded.viaGesture)
        assertEquals("FOUR_WAY", recorded.controlScheme)
        assertTrue(harness.analytics.events.any { it is TriageEvent.DecisionUndone })
        assertTrue(harness.analytics.events.any { it is TriageEvent.DeckOpened })
    }

    @Test
    fun `an unresolved decision from a previous session is retried on open`() = runTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        harness.repository.record(
            com.codingpit.muviss.feature.triage.domain.TriageDecision.of(filmB, TriageVerdict.LATER, NOW_EPOCH_MS, resolved = false),
        )

        harness.viewModel()
        advanceUntilIdle()

        assertEquals(listOf(filmB.id), harness.collection.added)
        assertTrue(harness.repository.decisions.value.getValue(filmB.id).resolved)
    }
}
