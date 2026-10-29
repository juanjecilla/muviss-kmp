package com.codingpit.muviss.feature.nextpick.ui

import com.codingpit.muviss.feature.nextpick.api.PickSource
import com.codingpit.muviss.feature.nextpick.api.SessionLength
import com.codingpit.muviss.feature.nextpick.domain.PickAnswer
import com.codingpit.muviss.feature.nextpick.domain.PickContext
import com.codingpit.muviss.feature.nextpick.domain.PickQuestion
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PickSessionViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        pool: List<com.codingpit.muviss.feature.nextpick.domain.PickCandidate>,
        context: PickContext,
        actions: RecordingNextPickActions = RecordingNextPickActions(),
    ) = PickSessionViewModel(context = { context }, engine = engineOver(pool), actions = actions)

    @Test
    fun online_session_opens_on_the_first_question_out_of_the_standard_length() {
        // Given an online person with a backlog and the standard length
        val viewModel = viewModel(listOf(candidate("1")), PickContext(length = SessionLength.STANDARD, online = true, hasBacklog = true))

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
        val viewModel = viewModel(listOf(candidate("1")), PickContext(length = SessionLength.QUICK, online = false, hasBacklog = true))

        // When the session opens
        val asking = assertIs<PickSessionUiState.Asking>(viewModel.state.value)

        // Then the source question is skipped: only the library can answer offline
        assertTrue(asking.offline)
        assertTrue(asking.question != PickQuestion.SOURCE)
    }

    @Test
    fun answering_every_question_ends_in_a_pick_for_the_person_to_act_on() {
        // Given a quick session over a pool with one title
        val viewModel = viewModel(listOf(candidate("1", "Dune")), PickContext(length = SessionLength.QUICK, online = true, hasBacklog = true))

        // When the person answers the source question and skips the next
        viewModel.answer(PickAnswer.Source(PickSource.EITHER))
        viewModel.skipQuestion()

        // Then the session ends in the one title the pool had
        val result = assertIs<PickSessionUiState.Result>(viewModel.state.value)
        assertEquals("Dune", result.pick.candidate.summary.title)
        assertEquals(null, result.outcome)
    }

    @Test
    fun an_empty_pool_says_nothing_fits_rather_than_inventing_a_pick() {
        // Given a session over nothing at all
        val viewModel = viewModel(emptyList(), PickContext(length = SessionLength.NONE, online = true, hasBacklog = true))

        // When the session has no questions and no candidates
        // Then it says nothing fits
        assertIs<PickSessionUiState.NoPick>(viewModel.state.value)
    }

    @Test
    fun add_records_the_outcome_and_calls_the_add_action_once() {
        // Given a session that ended in a pick
        val actions = RecordingNextPickActions()
        val viewModel = viewModel(listOf(candidate("7")), PickContext(length = SessionLength.NONE, online = true, hasBacklog = true), actions)

        // When the person adds it
        viewModel.add()

        // Then the Add action ran for that title and the screen knows the outcome
        assertEquals(listOf(MediaId.tmdbMovie("7")), actions.added)
        assertEquals(PickOutcome.ADDED, assertIs<PickSessionUiState.Result>(viewModel.state.value).outcome)
    }

    @Test
    fun back_steps_to_the_previous_question_and_reports_false_on_the_first() {
        // Given a session that has moved past its first question
        val viewModel = viewModel(listOf(candidate("1")), PickContext(length = SessionLength.STANDARD, online = true, hasBacklog = true))
        viewModel.answer(PickAnswer.Source(PickSource.MY_LIST))

        // When the person goes back
        val wentBack = viewModel.back()

        // Then the earlier question is shown again, and back on the first question says so
        assertTrue(wentBack)
        assertEquals(PickQuestion.SOURCE, assertIs<PickSessionUiState.Asking>(viewModel.state.value).question)
        assertEquals(false, viewModel.back())
    }
}
