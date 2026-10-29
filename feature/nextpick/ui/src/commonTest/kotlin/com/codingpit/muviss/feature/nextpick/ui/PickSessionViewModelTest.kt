package com.codingpit.muviss.feature.nextpick.ui

import com.codingpit.muviss.feature.nextpick.api.PickSource
import com.codingpit.muviss.feature.nextpick.domain.CandidateSource
import com.codingpit.muviss.feature.nextpick.domain.NextPickResultMode
import com.codingpit.muviss.feature.nextpick.domain.NextPickSettings
import com.codingpit.muviss.feature.nextpick.domain.PickAnswer
import com.codingpit.muviss.feature.nextpick.domain.PickCandidate
import com.codingpit.muviss.feature.nextpick.domain.PickContext
import com.codingpit.muviss.feature.nextpick.domain.PickQuestion
import com.codingpit.muviss.feature.nextpick.domain.PickSessionLength
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PickSessionViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val noQuestions = PickContext(length = PickSessionLength.NONE, online = true, hasBacklog = true)

    private fun viewModel(
        pool: List<PickCandidate>,
        context: PickContext,
        actions: RecordingNextPickActions = RecordingNextPickActions(),
        mode: NextPickResultMode = NextPickResultMode.ONE_PICK,
    ) = PickSessionViewModel(
        context = { context },
        engine = engineOver(pool),
        actions = actions,
        settings = FixedSettings(mode),
    )

    @Test
    fun online_session_opens_on_the_first_question_out_of_the_standard_length() {
        // Given an online person with a backlog and the standard length
        val viewModel = viewModel(listOf(candidate("1")), PickContext(length = PickSessionLength.STANDARD, online = true, hasBacklog = true))

        // When the session opens
        val state = viewModel.state.value

        // Then the first question is asked, counted as step one of four
        val asking = assertIs<PickSessionUiState.Asking>(state)
        assertEquals(1, asking.step)
        assertEquals(4, asking.total)
        assertEquals(PickQuestion.SOURCE, asking.question)
    }

    @Test
    fun offline_session_never_asks_where_the_pick_comes_from() {
        // Given the person is offline
        val viewModel = viewModel(listOf(candidate("1")), PickContext(length = PickSessionLength.QUICK, online = false, hasBacklog = true))

        // When the session opens
        val asking = assertIs<PickSessionUiState.Asking>(viewModel.state.value)

        // Then the source question is skipped: only the library can answer offline
        assertTrue(asking.offline)
        assertTrue(asking.question != PickQuestion.SOURCE)
    }

    @Test
    fun answering_every_question_ends_in_a_pick_for_the_person_to_act_on() {
        // Given a quick session over a pool with one title
        val viewModel = viewModel(listOf(candidate("1", "Dune")), PickContext(length = PickSessionLength.QUICK, online = true, hasBacklog = true))

        // When the person answers the source question and skips the next
        viewModel.answer(PickAnswer.Source(PickSource.EITHER))
        viewModel.skipQuestion()

        // Then the session ends in the one title the pool had, with no outcome yet
        val result = assertIs<PickSessionUiState.Result>(viewModel.state.value)
        assertEquals("Dune", result.picks.single().candidate.summary.title)
        assertTrue(result.outcomes.isEmpty())
    }

    @Test
    fun an_empty_pool_says_nothing_fits_rather_than_inventing_a_pick() {
        // Given a session over nothing at all
        val viewModel = viewModel(emptyList(), noQuestions)

        // When the session has no questions and no candidates
        // Then it says nothing fits
        assertIs<PickSessionUiState.NoPick>(viewModel.state.value)
    }

    @Test
    fun add_records_the_outcome_for_that_title_and_calls_the_add_action_once() {
        // Given a session that ended in a pick
        val actions = RecordingNextPickActions()
        val viewModel = viewModel(listOf(candidate("7")), noQuestions, actions)

        // When the person adds it
        viewModel.add(MediaId.tmdbMovie("7"))

        // Then the Add action ran for that title and the screen knows the outcome for it
        assertEquals(listOf(MediaId.tmdbMovie("7")), actions.added)
        assertEquals(PickOutcome.ADDED, assertIs<PickSessionUiState.Result>(viewModel.state.value).outcomes[MediaId.tmdbMovie("7")])
    }

    @Test
    fun a_second_tap_on_an_acted_row_does_nothing() {
        // Given a title already refused
        val actions = RecordingNextPickActions()
        val viewModel = viewModel(listOf(candidate("7")), noQuestions, actions)
        viewModel.notForMe(MediaId.tmdbMovie("7"))

        // When the same row is tapped again
        viewModel.add(MediaId.tmdbMovie("7"))

        // Then no second write happens
        assertEquals(listOf(MediaId.tmdbMovie("7")), actions.refused)
        assertTrue(actions.added.isEmpty())
    }

    @Test
    fun back_steps_to_the_previous_question_and_reports_false_on_the_first() {
        // Given a session that has moved past its first question
        val viewModel = viewModel(listOf(candidate("1")), PickContext(length = PickSessionLength.STANDARD, online = true, hasBacklog = true))
        viewModel.answer(PickAnswer.Source(PickSource.MY_LIST))

        // When the person goes back
        val wentBack = viewModel.back()

        // Then the earlier question is shown again, and back on the first question says so
        assertTrue(wentBack)
        assertEquals(PickQuestion.SOURCE, assertIs<PickSessionUiState.Asking>(viewModel.state.value).question)
        assertEquals(false, viewModel.back())
    }

    @Test
    fun top_picks_draws_five_titles_in_the_engines_order() {
        // Given a pool of seven titles
        val pool = (1..7).map { candidate("$it") }

        // When the session ends in Top Picks
        val result = assertIs<PickSessionUiState.Result>(viewModel(pool, noQuestions, mode = NextPickResultMode.TOP_PICKS).state.value)

        // Then five distinct titles are drawn, and they are the top of the same list One Pick starts from
        assertEquals(NextPickResultMode.TOP_PICKS, result.mode)
        assertEquals(5, result.picks.size)
        assertEquals(5, result.picks.map { it.candidate.id }.toSet().size)
        val onePick = assertIs<PickSessionUiState.Result>(viewModel(pool, noQuestions).state.value)
        assertEquals(onePick.picks.single(), result.picks.first())
    }

    @Test
    fun another_in_one_pick_steps_to_a_title_never_drawn_before() {
        // Given a one-pick session over three titles
        val pool = (1..3).map { candidate("$it") }
        val viewModel = viewModel(pool, noQuestions)
        val first = assertIs<PickSessionUiState.Result>(viewModel.state.value).picks.single().candidate.id

        // When the person asks for another, twice
        viewModel.another()
        val second = assertIs<PickSessionUiState.Result>(viewModel.state.value).picks.single().candidate.id
        viewModel.another()
        val third = assertIs<PickSessionUiState.Result>(viewModel.state.value).picks.single().candidate.id

        // Then each one is new
        assertEquals(3, setOf(first, second, third).size)
    }

    @Test
    fun a_refused_row_in_top_picks_leaves_the_other_rows_actionable() {
        // Given five picks on screen
        val actions = RecordingNextPickActions()
        val viewModel = viewModel((1..5).map { candidate("$it") }, noQuestions, actions, NextPickResultMode.TOP_PICKS)
        val refused = assertIs<PickSessionUiState.Result>(viewModel.state.value).picks.first().candidate.id

        // When the first row is refused
        viewModel.notForMe(refused)

        // Then only that row carries an outcome, and only it was written
        val result = assertIs<PickSessionUiState.Result>(viewModel.state.value)
        assertEquals(mapOf(refused to PickOutcome.NOT_FOR_ME), result.outcomes)
        assertEquals(listOf(refused), actions.refused)
    }

    @Test
    fun deck_seeds_are_only_the_picks_the_library_does_not_hold() {
        // Given a pool with one title already in the library and one not
        val inLibrary = candidate("1", "In library").copy(libraryStatus = WatchStatus.NOT_STARTED, source = CandidateSource.BACKLOG)
        val fresh = candidate("2", "Fresh")
        val viewModel = viewModel(listOf(inLibrary, fresh), noQuestions, mode = NextPickResultMode.DECK)

        // When the person opens the Deck
        val seeds = viewModel.deckSeeds()

        // Then only the title the library does not hold is handed to Triage, because Triage excludes the library
        assertEquals(listOf(MediaId.tmdbMovie("2")), seeds.map { it.id })
        assertNotEquals(inLibrary.id, seeds.single().id)
    }
}

private class FixedSettings(override val mode: NextPickResultMode) : NextPickSettings {
    override val length: PickSessionLength = PickSessionLength.NONE
}
