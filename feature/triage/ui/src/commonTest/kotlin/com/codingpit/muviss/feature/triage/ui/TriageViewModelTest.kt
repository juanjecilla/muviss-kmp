@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.triage.ui

import com.codingpit.muviss.core.common.flags.TriageControlScheme
import com.codingpit.muviss.core.designsystem.text.resolveAsync
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.feature.triage.domain.DeckCursorStore
import com.codingpit.muviss.feature.triage.domain.SavedDeckCursor
import com.codingpit.muviss.feature.triage.domain.TriageEvent
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.GenreKey
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MetadataError
import com.codingpit.muviss.models.MutedGenres
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
        assertEquals(listOf(filmB.id), vm.state.value.backingCards.map { it.id })
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
        harness.details.failure = MetadataError.Offline()
        val vm = harness.viewModel()
        advanceUntilIdle()

        vm.onDecide(TriageVerdict.LATER, viaGesture = false)
        advanceUntilIdle()

        val failed = assertNotNull(vm.state.value.failedCommit)
        assertEquals(filmA.id, failed.summary.id)
        assertEquals(MetadataError.Offline().userMessage, failed.message.resolveAsync())
        assertFalse(harness.repository.decisions.value.getValue(filmA.id).resolved)
    }

    @Test
    fun `a failed commit replaces the undo so the failure is the only snackbar`() = runTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        harness.details.failure = IllegalStateException("offline")
        val vm = harness.viewModel()
        advanceUntilIdle()

        vm.onDecide(TriageVerdict.LATER, viaGesture = false)
        advanceUntilIdle()

        assertNotNull(vm.state.value.failedCommit)
        assertNull(vm.state.value.undoable)
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
    fun `undo says which way the card left so the screen can play it backwards`() = runTest {
        val vm = TriageHarness(movies = listOf(filmA, filmB)).viewModel()
        advanceUntilIdle()
        vm.onDecide(TriageVerdict.LATER, viaGesture = true)
        advanceUntilIdle()

        vm.onUndo()
        advanceUntilIdle()

        val restored = assertNotNull(vm.state.value.restored)
        assertEquals(filmA.id, restored.id)
        // The verdict is what the direction is derived from — Later went right,
        // so the card comes back from the right.
        assertEquals(TriageVerdict.LATER, restored.verdict)
    }

    @Test
    fun `undoing the same card twice reads as two separate arrivals`() = runTest {
        val vm = TriageHarness(movies = listOf(filmA, filmB)).viewModel()
        advanceUntilIdle()

        vm.onDecide(TriageVerdict.SKIP, viaGesture = true)
        vm.onUndo()
        advanceUntilIdle()
        val first = assertNotNull(vm.state.value.restored).token

        vm.onDecide(TriageVerdict.SKIP, viaGesture = true)
        vm.onUndo()
        advanceUntilIdle()

        // Same card, same verdict: without the token the screen would see an
        // unchanged value and never replay the entrance.
        assertTrue(assertNotNull(vm.state.value.restored).token > first)
    }

    @Test
    fun `deciding again clears the card undo put back`() = runTest {
        val vm = TriageHarness(movies = listOf(filmA, filmB)).viewModel()
        advanceUntilIdle()
        vm.onDecide(TriageVerdict.SKIP, viaGesture = true)
        vm.onUndo()
        advanceUntilIdle()

        vm.onDecide(TriageVerdict.SKIP, viaGesture = true)
        advanceUntilIdle()

        // Left standing, the next card would be treated as an arrival and fly
        // in from the side the previous one came back on.
        assertNull(vm.state.value.restored)
    }

    @Test
    fun `the deck animates only while both motion flags are on`() = runTest {
        val harness = TriageHarness(movies = listOf(filmA))
        val vm = harness.viewModel()
        advanceUntilIdle()
        assertTrue(vm.state.value.deckAnimations)

        harness.flags.setTriageDeckAnimations(false)
        advanceUntilIdle()
        assertFalse(vm.state.value.deckAnimations)

        harness.flags.setTriageDeckAnimations(true)
        harness.flags.setAnimationsEnabled(false)
        advanceUntilIdle()
        // The app-wide switch wins on its own: it is the master, not a peer.
        assertFalse(vm.state.value.deckAnimations)
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
        val harness = TriageHarness(movies = listOf(filmA), flagsConfig = FakeFlagsConfig(scheme = TriageControlScheme.THREE_WAY))
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
    fun `a batch that comes back empty keeps searching rather than declaring the deck caught up`() = runTest {
        // DeckLoader bounds a single load() call to a handful of catalogue
        // pages (MAX_PAGES_PER_BATCH), so deep into a large library — where
        // TMDB's popularity order front-loads exactly the titles a long-time
        // user already has an opinion about — one batch can come back with
        // zero *new* titles while the catalogue itself (ten pages here) is
        // nowhere near exhausted. "All caught up" must mean the catalogue is
        // genuinely spent, not just that one 5-page batch found nothing.
        val freshTitle = movie("fresh-1")
        val byNumber = (1..5).associateWith { emptyList<MediaSummary>() } + (6 to listOf(freshTitle))
        val vm = TriageHarness(moviePages = FakeDeckPages(byNumber, totalPages = 10)).viewModel()
        advanceUntilIdle()

        assertEquals(freshTitle.id, vm.state.value.topCard?.id, "a single empty batch is not proof the catalogue ran out")
        assertFalse(vm.state.value.exhausted)
    }

    @Test
    fun a_swipe_during_a_refill_does_not_restart_it() = runTest {
        // #722: a refill is held in flight on its next page; more swipes must not cancel it and ask for that
        // page again. Each page of this refill is requested exactly once.
        val pages = (1..10).associateWith { n -> listOf(movie("p$n-a"), movie("p$n-b")) }
        val harness = TriageHarness(moviePages = FakeDeckPages(pages, totalPages = 10))
        val vm = harness.viewModel()
        advanceUntilIdle()
        assertEquals(10, vm.state.value.cards.size, "the deck is filled to its target before the first swipe")

        val gate = CompletableDeferred<Unit>()
        harness.source.gate = gate
        val before = harness.source.requestedPages.size
        repeat(5) { vm.onDecide(TriageVerdict.LATER, viaGesture = false) } // the fifth swipe starts a refill
        assertTrue(harness.source.requestedPages.size > before, "the refill reached the gate before the next swipe")
        vm.onDecide(TriageVerdict.LATER, viaGesture = false) // the sixth lands in flight
        gate.complete(Unit)
        advanceUntilIdle()

        val requested = harness.source.requestedPages.drop(before)
        assertEquals(requested.distinct(), requested, "no page was asked for twice, read $requested")
        assertTrue(requested.isNotEmpty())
    }

    @Test
    fun the_deck_is_filled_to_ten_and_refilled_when_it_falls_to_five() = runTest {
        val pages = (1..10).associateWith { n -> listOf(movie("p$n-a"), movie("p$n-b")) }
        val vm = TriageHarness(moviePages = FakeDeckPages(pages, totalPages = 10)).viewModel()
        advanceUntilIdle()
        assertEquals(10, vm.state.value.cards.size)

        repeat(5) { vm.onDecide(TriageVerdict.LATER, viaGesture = false) }
        advanceUntilIdle()

        assertEquals(10, vm.state.value.cards.size, "five swipes left five cards, which is the low-water mark, so the deck refills to ten")
    }

    @Test
    fun a_card_read_past_the_refill_target_is_kept_and_shown_before_any_new_page() = runTest {
        // Pages of two cards: a refill that needs five cards reads three pages and holds the sixth card back.
        val pages = (1..10).associateWith { n -> listOf(movie("p$n-a"), movie("p$n-b")) }
        val harness = TriageHarness(moviePages = FakeDeckPages(pages, totalPages = 10))
        val vm = harness.viewModel()
        advanceUntilIdle()
        repeat(5) { vm.onDecide(TriageVerdict.LATER, viaGesture = false) }
        advanceUntilIdle()
        assertTrue(movie("p8-b").id !in vm.state.value.cards.map { it.id }, "held back, not yet on the deck")

        repeat(5) { vm.onDecide(TriageVerdict.LATER, viaGesture = false) }
        advanceUntilIdle()

        val ids = vm.state.value.cards.map { it.id }
        assertTrue(movie("p8-b").id in ids, "the held-back card from page 8 reaches the deck before any new page: ${ids.map { it.external }}")
        assertEquals(ids.distinct(), ids, "a kept card is never shown twice")
    }

    @Test
    fun a_new_visit_resumes_near_the_saved_position_and_skips_what_was_decided() = runTest {
        val pages = (1..12).associateWith { n -> listOf(movie("p$n-a"), movie("p$n-b")) }
        val store = FakeDeckCursorStore()
        val harness = TriageHarness(moviePages = FakeDeckPages(pages, totalPages = 12), cursors = store)
        val first = harness.viewModel()
        advanceUntilIdle()
        // Act on every card of pages 1 to 4. Page 5 is still unacted, so that is where a visit must resume.
        repeat(8) { first.onDecide(TriageVerdict.LATER, viaGesture = false) }
        advanceUntilIdle()
        // The position moves only once each decision persists. Every save has landed once the scheduler is idle.
        advanceUntilIdle()
        assertEquals(5, store.saved.values.single().moviesResumePage, "the last decision persisted, so the resume page is the first unacted one")

        val before = harness.source.requestedPages.size
        val second = harness.viewModel()
        advanceUntilIdle()

        val firstRequest = harness.source.requestedPages.drop(before).first()
        assertTrue(firstRequest > 1, "a new visit does not start over at page 1, asked for page $firstRequest")
        assertTrue(second.state.value.cards.none { it.id.external in setOf("p1-a", "p1-b", "p2-a", "p2-b", "p3-a", "p3-b", "p4-a", "p4-b") }, "decided titles stay out")
        assertTrue(second.state.value.cards.isNotEmpty())
    }

    @Test
    fun a_reset_cancels_the_refill_in_flight_and_starts_from_page_one() = runTest {
        val pages = (1..10).associateWith { n -> listOf(movie("p$n-a"), movie("p$n-b")) }
        val harness = TriageHarness(moviePages = FakeDeckPages(pages, totalPages = 10))
        val vm = harness.viewModel()
        advanceUntilIdle()

        val gate = CompletableDeferred<Unit>()
        harness.source.gate = gate
        val beforeSwipes = harness.source.requestedPages.size
        repeat(5) { vm.onDecide(TriageVerdict.LATER, viaGesture = false) } // a refill now waits on the gate
        assertTrue(harness.source.requestedPages.size > beforeSwipes, "the refill reached the gate before the reset")
        val beforeReset = harness.source.requestedPages.size
        vm.retry() // an explicit start over
        harness.source.gate = null
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, harness.source.requestedPages.drop(beforeReset).first(), "the reset reads from page 1 again: ${harness.source.requestedPages}")
        val ids = vm.state.value.cards.map { it.id }
        assertEquals(ids.distinct(), ids, "the superseded refill's results are dropped, not merged in")
    }

    @Test
    fun `a long run of empty batches stops and offers to keep looking instead of searching forever`() = runTest {
        // #183: deep in a heavily-triaged catalogue, every batch can come back
        // empty for dozens of pages. The refill stops after a bounded number of
        // empty batches rather than holding a bare spinner for minutes.
        val emptyPages = TriageViewModel.MAX_PAGES_PER_REFILL * 2
        val fresh = movie("far-back")
        val byNumber = (1..emptyPages).associateWith { emptyList<MediaSummary>() } + ((emptyPages + 1) to listOf(fresh))
        val harness = TriageHarness(moviePages = FakeDeckPages(byNumber, totalPages = emptyPages + 10))
        val vm = harness.viewModel()
        advanceUntilIdle()

        assertNull(vm.state.value.topCard)
        assertTrue(vm.state.value.keepLookingAvailable, "the catalogue is not spent, so this is not caught up")
        assertFalse(vm.state.value.exhausted)
        assertFalse(vm.state.value.searchingDeeper)
        val requestsBeforeStopping = harness.source.requestedPages.size
        val bound = TriageViewModel.MAX_PAGES_PER_REFILL
        assertTrue(requestsBeforeStopping in 1..bound, "one refill reads at most $bound pages, read $requestsBeforeStopping")
        val furthest = harness.source.requestedPages.max()

        // Keep looking reads on from the cursor; it never starts over at page 1.
        vm.onKeepLooking()
        advanceUntilIdle()
        val second = harness.source.requestedPages.drop(requestsBeforeStopping)
        assertTrue(second.size in 1..bound, "the next refill is bounded too, read ${second.size}")
        assertTrue(second.all { it > furthest }, "keep looking continues past page $furthest, read $second")
        assertTrue(vm.state.value.keepLookingAvailable)

        vm.onKeepLooking()
        advanceUntilIdle()
        assertEquals(fresh.id, vm.state.value.topCard?.id)
        assertFalse(vm.state.value.keepLookingAvailable)
    }

    @Test
    fun `a catalogue that genuinely runs out is caught up and offers nothing more`() = runTest {
        val byNumber = (1..3).associateWith { emptyList<MediaSummary>() }
        val vm = TriageHarness(moviePages = FakeDeckPages(byNumber, totalPages = 3)).viewModel()
        advanceUntilIdle()

        assertTrue(vm.state.value.exhausted)
        assertFalse(vm.state.value.keepLookingAvailable)
    }

    @Test
    fun `a load failure surfaces as a retryable error`() = runTest {
        val harness = TriageHarness(movies = listOf(filmA))
        harness.source.failure = IllegalStateException("Unable to resolve host api.themoviedb.org?api_key=SECRET")
        val vm = harness.viewModel()
        advanceUntilIdle()

        // The raw text is discarded; a screen only ever gets fixed copy.
        assertEquals("Couldn't load more titles.", vm.state.value.error?.resolveAsync())

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

    private val talk = GenreKey(MediaType.TV, "10767")
    private val mutedTalk = MutedGenres(setOf(talk))
    private val kimmel = show("20").copy(genreIds = listOf("10767", "35"))
    private val drama = show("21").copy(genreIds = listOf("18"))

    @Test
    fun `muted genres are asked of the source and keep their titles out of the first deck`() = runTest {
        val harness = TriageHarness(tv = listOf(kimmel, drama), muted = mutedTalk)
        val vm = harness.viewModel()
        advanceUntilIdle()

        assertEquals(listOf(drama.id), vm.state.value.cards.map { it.id })
        assertTrue(vm.state.value.hasMuted)
    }

    @Test
    fun `the first deck is never loaded unmuted and then reloaded`() = runTest {
        val harness = TriageHarness(tv = listOf(kimmel, drama), muted = mutedTalk)
        harness.viewModel()
        advanceUntilIdle()

        // Loading before the setting arrived would flash exactly the shows the user muted.
        assertTrue(harness.source.tvExcludedRequests.isNotEmpty())
        assertTrue(harness.source.tvExcludedRequests.all { it == setOf("10767") })
    }

    @Test
    fun `the show muted chip brings the muted titles back and toggling again hides them`() = runTest {
        val harness = TriageHarness(tv = listOf(kimmel, drama), muted = mutedTalk)
        val vm = harness.viewModel()
        advanceUntilIdle()

        vm.onShowMutedToggled()
        advanceUntilIdle()
        val shown = vm.state.value.cards.map { it.id }.toSet()
        assertTrue(vm.state.value.showMuted)

        vm.onShowMutedToggled()
        advanceUntilIdle()

        assertEquals(setOf(kimmel.id, drama.id), shown)
        assertEquals(listOf(drama.id), vm.state.value.cards.map { it.id })
        assertFalse(vm.state.value.showMuted)
    }

    @Test
    fun `changing the muted genres in Settings reloads the deck from the first page`() = runTest {
        val harness = TriageHarness(tv = listOf(kimmel, drama))
        val vm = harness.viewModel()
        advanceUntilIdle()
        assertEquals(setOf(kimmel.id, drama.id), vm.state.value.cards.map { it.id }.toSet())

        harness.settings.configured.value = mutedTalk
        advanceUntilIdle()

        assertEquals(listOf(drama.id), vm.state.value.cards.map { it.id })
    }

    @Test
    fun `muting alone does not make the empty deck offer to clear filters`() = runTest {
        val vm = TriageHarness(tv = emptyList(), muted = mutedTalk).viewModel()
        advanceUntilIdle()

        assertFalse(vm.state.value.filter.isNarrowed)
    }

    @Test
    fun `switching type keeps muting and clearing filters keeps it too`() = runTest {
        val vm = TriageHarness(tv = listOf(drama), muted = mutedTalk).viewModel()
        advanceUntilIdle()

        vm.onTypeFilterChange(MediaType.TV)
        assertEquals(mutedTalk, vm.state.value.filter.muted)
        vm.onClearFilters()
        assertEquals(mutedTalk, vm.state.value.filter.muted)
    }

    @Test
    fun `the chip is not offered when nothing is muted`() = runTest {
        val vm = TriageHarness(tv = listOf(drama)).viewModel()
        advanceUntilIdle()

        assertFalse(vm.state.value.hasMuted)
    }

    @Test
    fun a_decision_that_fails_to_record_keeps_its_page_in_the_saved_position() = runTest {
        // Page 1 is the only card on page 1, so once it is decided the earliest unacted page is 2 — unless the
        // failed decision still holds page 1 (#722): then a restart resumes at 1 and cannot skip the card.
        val pages = FakeDeckPages(mapOf(1 to listOf(movie("p1-a")), 2 to listOf(movie("p2-a"), movie("p2-b"))), totalPages = 2)
        val store = FakeDeckCursorStore()
        val harness = TriageHarness(moviePages = pages, cursors = store)
        harness.details.failure = IllegalStateException("offline")
        val vm = harness.viewModel()
        advanceUntilIdle()

        vm.onDecide(TriageVerdict.LATER, viaGesture = false)
        advanceUntilIdle()

        assertNotNull(vm.state.value.failedCommit)
        assertEquals(1, store.saved.values.single().moviesResumePage, "the unpersisted card keeps page 1 in the saved position")
    }

    @Test
    fun a_checkpoint_taken_before_a_reset_cannot_land_after_the_reset_position() = runTest {
        // Writes are held in the store and released newest first, so an out-of-order landing would show up as
        // a pre-reset value written last. Every write must land in the order it was taken, and the last one
        // to land must come from after the reset.
        val pages = (1..10).associateWith { n -> listOf(movie("p$n-a"), movie("p$n-b")) }
        val store = HeldCursorStore()
        val harness = TriageHarness(moviePages = FakeDeckPages(pages, totalPages = 10), cursors = store)
        val vm = harness.viewModel()
        advanceUntilIdle()

        store.holding = true
        repeat(5) { vm.onDecide(TriageVerdict.LATER, viaGesture = false) } // checkpoints taken before the reset
        advanceUntilIdle()
        store.phase = 1
        vm.retry()
        advanceUntilIdle()

        store.holding = false
        while (store.held.isNotEmpty()) {
            store.held.removeLast().complete(Unit)
            advanceUntilIdle()
        }

        assertEquals(1, store.landed.last(), "the last checkpoint to land was taken after the reset, landed: ${store.landed}")
    }
}

/**
 * A [DeckCursorStore] whose writes wait for the test while [holding] is set, so a checkpoint can stay in flight
 * across a reset. [landed] records, in the order they landed, the phase each write was issued in.
 */
private class HeldCursorStore : DeckCursorStore {
    val saved = mutableMapOf<String, SavedDeckCursor>()
    var holding = false

    /** 0 until the test starts the reset; a write records the phase it was issued in. */
    var phase = 0
    val held = ArrayDeque<CompletableDeferred<Unit>>()
    val landed = mutableListOf<Int>()

    override suspend fun read(key: String): SavedDeckCursor? = saved[key]

    override suspend fun write(key: String, saved: SavedDeckCursor) {
        val issuedIn = phase
        if (holding) {
            val gate = CompletableDeferred<Unit>()
            held.addLast(gate)
            gate.await()
        }
        this.saved[key] = saved
        landed += issuedIn
    }
}
