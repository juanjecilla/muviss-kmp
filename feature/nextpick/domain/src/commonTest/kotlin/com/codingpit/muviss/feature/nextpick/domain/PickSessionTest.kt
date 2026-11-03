package com.codingpit.muviss.feature.nextpick.domain

import com.codingpit.muviss.feature.nextpick.api.PickAnchor
import com.codingpit.muviss.feature.nextpick.api.PickCommitment
import com.codingpit.muviss.feature.nextpick.api.PickCompany
import com.codingpit.muviss.feature.nextpick.api.PickCriteria
import com.codingpit.muviss.feature.nextpick.api.PickEra
import com.codingpit.muviss.feature.nextpick.api.PickMood
import com.codingpit.muviss.feature.nextpick.api.PickRuntime
import com.codingpit.muviss.feature.nextpick.api.PickSource
import com.codingpit.muviss.feature.nextpick.api.SessionLength
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PickSessionTest {

    private val finished = MediaId.tmdbTv("1399")

    /** Skips every question the session offers and returns the ones it offered, in order. */
    private fun questionsAskedWhenSkippingAll(context: PickContext): List<PickQuestion> {
        var session = PickSession.start(context)
        val asked = mutableListOf<PickQuestion>()
        while (true) {
            val q = session.question ?: break
            asked += q
            session = session.skip()
        }
        return asked
    }

    @Test
    fun a_quick_session_asks_two_questions_and_then_is_complete() {
        val asked = questionsAskedWhenSkippingAll(PickContext(length = SessionLength.QUICK))
        assertEquals(listOf(PickQuestion.SOURCE, PickQuestion.FORMAT_AND_TIME), asked)
    }

    @Test
    fun a_standard_session_asks_up_to_four() {
        val asked = questionsAskedWhenSkippingAll(PickContext(length = SessionLength.STANDARD))
        assertEquals(listOf(PickQuestion.SOURCE, PickQuestion.FORMAT_AND_TIME, PickQuestion.MOOD, PickQuestion.ERA_AND_SERVICES), asked)
    }

    @Test
    fun a_session_with_no_questions_is_complete_at_once_with_the_starting_criteria() {
        val session = PickSession.start(PickContext(length = SessionLength.NONE))
        assertTrue(session.isComplete)
        assertNull(session.question)
        assertEquals(PickCriteria(), session.criteria)
    }

    @Test
    fun the_same_context_and_answers_give_the_same_questions() {
        val context = PickContext(length = SessionLength.STANDARD, hasWatchedTitles = true, anchorCandidate = finished)
        assertEquals(questionsAskedWhenSkippingAll(context), questionsAskedWhenSkippingAll(context))
    }

    @Test
    fun every_question_is_skippable_and_a_skip_adds_no_constraint_but_spends_a_slot() {
        var session = PickSession.start(PickContext(length = SessionLength.STANDARD))
        session = session.skip().skip()
        assertEquals(PickCriteria(), session.criteria)
        assertEquals(2, session.asked)
        assertEquals(PickQuestion.MOOD, session.question)
    }

    @Test
    fun answers_build_the_criteria() {
        var session = PickSession.start(PickContext(length = SessionLength.STANDARD, hasWatchedTitles = true))
        session = session.answer(PickAnswer.Source(PickSource.SOMETHING_NEW))
        session = session.answer(PickAnswer.FormatAndTime(MediaType.TV, PickRuntime.EVENING))
        session = session.answer(PickAnswer.Mood(setOf(PickMood.LIGHT)))
        session = session.answer(PickAnswer.EraAndServices(PickEra.CLASSIC, "es", onlyMyServices = false))
        assertTrue(session.isComplete)
        assertEquals(
            PickCriteria(
                source = PickSource.SOMETHING_NEW,
                type = MediaType.TV,
                runtime = PickRuntime.EVENING,
                moods = setOf(PickMood.LIGHT),
                era = PickEra.CLASSIC,
                originalLanguage = "es",
            ),
            session.criteria,
        )
    }

    @Test
    fun my_list_makes_the_catalogue_questions_pointless() {
        var session = PickSession.start(PickContext(length = SessionLength.STANDARD, anchorCandidate = finished, hasWatchedTitles = true))
        session = session.answer(PickAnswer.Source(PickSource.MY_LIST))
        val rest = mutableListOf<PickQuestion>()
        while (session.question != null) {
            rest += session.question!!
            session = session.skip()
        }
        assertFalse(PickQuestion.ANCHOR in rest)
        assertFalse(PickQuestion.ERA_AND_SERVICES in rest)
        assertEquals(listOf(PickQuestion.FORMAT_AND_TIME, PickQuestion.MOOD, PickQuestion.COMPANY), rest)
    }

    @Test
    fun something_new_never_asks_about_rewatching() {
        val context = PickContext(length = SessionLength.STANDARD, hasWatchedTitles = true)
        var session = PickSession.start(context).answer(PickAnswer.Source(PickSource.SOMETHING_NEW))
        val rest = mutableListOf<PickQuestion>()
        while (session.question != null) {
            rest += session.question!!
            session = session.skip()
        }
        assertFalse(PickQuestion.REWATCH in rest)
    }

    @Test
    fun the_anchor_is_only_asked_when_a_title_was_just_finished() {
        val without = questionsAskedWhenSkippingAll(PickContext(length = SessionLength.STANDARD))
        assertFalse(PickQuestion.ANCHOR in without)
        var session = PickSession.start(PickContext(length = SessionLength.STANDARD, anchorCandidate = finished))
        session = session.skip().skip().skip()
        assertEquals(PickQuestion.ANCHOR, session.question)
        session = session.answer(PickAnswer.Anchor(PickAnchor(finished, like = true)))
        assertEquals(PickAnchor(finished, like = true), session.criteria.anchor)
    }

    private fun askedAfter(context: PickContext, format: PickAnswer.FormatAndTime): List<PickQuestion> {
        var session = PickSession.start(context).answer(format)
        val asked = mutableListOf<PickQuestion>()
        while (session.question != null) {
            asked += session.question!!
            session = session.skip()
        }
        return asked
    }

    @Test
    fun a_movie_answer_removes_the_commitment_question() {
        // A profile that answers mood, era and company leaves commitment as the only candidate.
        val context = PickContext(
            length = SessionLength.STANDARD,
            hasBacklog = false,
            answeredByProfile = setOf(PickQuestion.MOOD, PickQuestion.ERA_AND_SERVICES, PickQuestion.COMPANY),
        )
        assertEquals(emptyList(), askedAfter(context, PickAnswer.FormatAndTime(MediaType.MOVIE, null)))
        assertEquals(listOf(PickQuestion.COMMITMENT), askedAfter(context, PickAnswer.FormatAndTime(MediaType.TV, null)))
        assertEquals(listOf(PickQuestion.COMMITMENT), askedAfter(context, PickAnswer.FormatAndTime(null, null)))
    }

    @Test
    fun commitment_is_asked_for_shows_once_earlier_questions_are_out_of_the_way() {
        // Skip the rest with a profile that answers mood, era and company, so commitment is next.
        val context = PickContext(
            length = SessionLength.STANDARD,
            hasBacklog = false,
            answeredByProfile = setOf(PickQuestion.MOOD, PickQuestion.ERA_AND_SERVICES, PickQuestion.COMPANY),
        )
        val session = PickSession.start(context).answer(PickAnswer.FormatAndTime(MediaType.TV, null))
        assertEquals(PickQuestion.COMMITMENT, session.question)
        assertEquals(PickCommitment.ENDED, session.answer(PickAnswer.Commitment(PickCommitment.ENDED)).criteria.commitment)
    }

    @Test
    fun offline_the_session_is_the_library_only_and_asks_nothing_about_the_catalogue() {
        val context = PickContext(length = SessionLength.STANDARD, online = false, anchorCandidate = finished, hasWatchedTitles = true)
        val session = PickSession.start(context)
        assertEquals(PickSource.MY_LIST, session.criteria.source)
        val asked = questionsAskedWhenSkippingAll(context)
        assertFalse(PickQuestion.SOURCE in asked)
        assertFalse(PickQuestion.ANCHOR in asked)
        assertFalse(PickQuestion.ERA_AND_SERVICES in asked)
    }

    @Test
    fun an_empty_backlog_goes_straight_to_the_catalogue_without_asking() {
        val context = PickContext(length = SessionLength.STANDARD, hasBacklog = false, hasWatchedTitles = true)
        val session = PickSession.start(context)
        assertEquals(PickSource.SOMETHING_NEW, session.criteria.source)
        assertFalse(PickQuestion.SOURCE in questionsAskedWhenSkippingAll(context))
        assertFalse(PickQuestion.REWATCH in questionsAskedWhenSkippingAll(context.copy(length = SessionLength.STANDARD)))
    }

    @Test
    fun a_filled_profile_shortens_the_session() {
        val context = PickContext(
            length = SessionLength.QUICK,
            answeredByProfile = setOf(PickQuestion.FORMAT_AND_TIME),
            startingCriteria = PickCriteria(type = MediaType.MOVIE),
        )
        val asked = questionsAskedWhenSkippingAll(context)
        assertEquals(listOf(PickQuestion.SOURCE, PickQuestion.MOOD), asked)
        assertEquals(MediaType.MOVIE, PickSession.start(context).criteria.type)
    }

    @Test
    fun services_are_only_offered_when_they_are_known() {
        assertFalse(PickSession.start(PickContext()).offersServices)
        assertTrue(PickSession.start(PickContext(servicesKnown = true)).offersServices)
    }

    @Test
    fun answering_a_question_that_was_not_asked_is_refused() {
        val session = PickSession.start(PickContext())
        assertFailsWith<IllegalArgumentException> { session.answer(PickAnswer.Mood(setOf(PickMood.LIGHT))) }
    }

    @Test
    fun answering_or_skipping_a_finished_session_is_refused() {
        val done = PickSession.start(PickContext(length = SessionLength.NONE))
        assertFailsWith<IllegalArgumentException> { done.skip() }
        assertFailsWith<IllegalArgumentException> { done.answer(PickAnswer.Company(PickCompany.ALONE)) }
    }

    @Test
    fun sets_the_caller_still_holds_cannot_change_an_open_session() {
        val profile = mutableSetOf<PickQuestion>()
        val moods = mutableSetOf(PickMood.LIGHT)
        val session = PickSession.start(PickContext(answeredByProfile = profile, startingCriteria = PickCriteria(moods = moods)))
        val first = session.question
        profile += PickQuestion.entries
        moods += PickMood.TENSE
        assertEquals(first, session.question)
        assertEquals(setOf(PickMood.LIGHT), session.criteria.moods)
    }

    @Test
    fun a_mood_answer_is_copied_when_it_is_accepted() {
        var session = PickSession.start(PickContext(length = SessionLength.STANDARD, online = false))
        while (session.question != PickQuestion.MOOD) session = session.skip()
        val picked = mutableSetOf(PickMood.ESCAPE)
        val after = session.answer(PickAnswer.Mood(picked))
        picked += PickMood.ROMANTIC
        assertEquals(setOf(PickMood.ESCAPE), after.criteria.moods)
    }
}
