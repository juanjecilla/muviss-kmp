package com.codingpit.muviss.feature.nextpick.domain

import com.codingpit.muviss.feature.nextpick.api.PickAnchor
import com.codingpit.muviss.feature.nextpick.api.PickCriteria
import com.codingpit.muviss.feature.nextpick.api.PickEra
import com.codingpit.muviss.feature.nextpick.api.PickMood
import com.codingpit.muviss.feature.nextpick.api.PickRuntime
import com.codingpit.muviss.feature.nextpick.api.PickSource
import com.codingpit.muviss.models.GenreKey
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MutedGenres
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class PickEngineTest {

    private fun summary(id: String, genres: List<String> = emptyList(), type: MediaType = MediaType.MOVIE, year: Int? = null, rating: Double? = null) = MediaSummary(if (type == MediaType.MOVIE) MediaId.tmdbMovie(id) else MediaId.tmdbTv(id), "Title $id", year, genreIds = genres, rating = rating)

    private fun backlog(id: String, genres: List<String> = emptyList(), status: WatchStatus = WatchStatus.NOT_STARTED, favorite: Boolean = false) = PickCandidate(summary(id, genres), CandidateSource.BACKLOG, libraryStatus = status, favorite = favorite)

    private fun fresh(id: String, genres: List<String> = emptyList(), source: CandidateSource = CandidateSource.DISCOVER, year: Int? = null, rating: Double? = null) = PickCandidate(summary(id, genres, year = year, rating = rating), source)

    private fun engine(
        vararg pool: PickCandidate,
        excluded: Set<MediaId> = emptySet(),
        muted: MutedGenres = MutedGenres.NONE,
        history: PickHistory = PickHistory(),
        ranker: PickRanker = RuleBasedRanker(),
    ) = PickEngine(
        sources = listOf(PickCandidates { _, _ -> pool.toList() }),
        exclusions = { excluded },
        history = { history },
        muting = { muted },
        ranker = ranker,
    )

    private fun reported(reporter: PickRankerReporter, ranker: PickRanker, vararg pool: PickCandidate) = PickEngine(
        sources = listOf(PickCandidates { _, _ -> pool.toList() }),
        exclusions = { emptySet() },
        history = { PickHistory() },
        muting = { MutedGenres.NONE },
        ranker = ranker,
        reporter = reporter,
    )

    /** Counts what [PickEngine] reports about a ranker that did not answer (#605). */
    private class RecordingReporter : PickRankerReporter {
        val failures = mutableListOf<String>()
        var timeouts = 0
        val unusable = mutableListOf<Int>()

        override fun failed(exceptionClass: String) {
            failures += exceptionClass
        }

        override fun timedOut() {
            timeouts++
        }

        override fun unusable(returned: Int) {
            unusable += returned
        }
    }

    private val context = PickContext()

    private fun ids(result: PickResult) = result.picks.map { it.candidate.summary.id.toString() }

    @Test
    fun a_title_the_person_ruled_on_or_snoozed_is_never_offered() = runTest {
        val result = engine(fresh("1"), fresh("2"), excluded = setOf(MediaId.tmdbMovie("1"))).pick(PickCriteria(), context)
        assertEquals(1, result.picks.size)
        assertEquals("Title 2", result.picks.single().candidate.summary.title)
    }

    @Test
    fun something_new_never_includes_a_title_the_library_knows() = runTest {
        val result = engine(backlog("1"), fresh("2")).pick(PickCriteria(source = PickSource.SOMETHING_NEW), context)
        assertEquals(listOf("Title 2"), result.picks.map { it.candidate.summary.title })
    }

    @Test
    fun my_list_offers_only_the_backlog() = runTest {
        val result = engine(backlog("1"), fresh("2")).pick(PickCriteria(source = PickSource.MY_LIST), context)
        assertEquals(listOf("Title 1"), result.picks.map { it.candidate.summary.title })
    }

    @Test
    fun a_title_in_progress_is_never_a_pick() = runTest {
        val result = engine(backlog("1", status = WatchStatus.WATCHING)).pick(PickCriteria(), context)
        assertTrue(result.picks.isEmpty())
    }

    @Test
    fun a_watched_title_comes_back_only_when_a_rewatch_is_fine() = runTest {
        val pool = arrayOf(backlog("1", status = WatchStatus.FINISHED), backlog("2"))
        assertEquals(listOf("Title 2"), engine(*pool).pick(PickCriteria(), context).picks.map { it.candidate.summary.title })
        val ok = engine(*pool).pick(PickCriteria(rewatchOk = true), context)
        assertEquals(setOf("Title 1", "Title 2"), ok.picks.map { it.candidate.summary.title }.toSet())
    }

    @Test
    fun muted_genres_apply_but_a_genre_picked_through_a_mood_is_shown() = runTest {
        val muted = MutedGenres(setOf(GenreKey(MediaType.MOVIE, "27"), GenreKey(MediaType.MOVIE, "35")))
        val pool = arrayOf(fresh("horror", listOf("27")), fresh("comedy", listOf("35")), fresh("drama", listOf("18")))

        val none = engine(*pool, muted = muted).pick(PickCriteria(), context)
        assertEquals(listOf("Title drama"), none.picks.map { it.candidate.summary.title })

        // Light reads as comedy (35): the explicit choice lifts that mute, horror stays muted.
        val light = engine(*pool, muted = muted).pick(PickCriteria(moods = setOf(PickMood.LIGHT)), context)
        assertEquals(setOf("Title comedy", "Title drama"), light.picks.map { it.candidate.summary.title }.toSet())
    }

    @Test
    fun the_same_title_from_two_sources_is_one_candidate_and_the_librarys_copy_wins() = runTest {
        val library = backlog("1", favorite = true)
        val catalogue = fresh("1", source = CandidateSource.RECOMMENDED)
        val result = PickEngine(
            sources = listOf(PickCandidates { _, _ -> listOf(catalogue) }, PickCandidates { _, _ -> listOf(library) }),
            exclusions = { emptySet() },
            history = { PickHistory() },
            muting = { MutedGenres.NONE },
        ).pick(PickCriteria(), context)
        assertEquals(1, result.picks.size)
        assertEquals(CandidateSource.BACKLOG, result.picks.single().candidate.source)
    }

    @Test
    fun offline_is_reported_so_the_screen_can_say_so() = runTest {
        assertTrue(engine(backlog("1")).pick(PickCriteria(source = PickSource.MY_LIST), PickContext(online = false)).offline)
        assertEquals(false, engine(backlog("1")).pick(PickCriteria(), context).offline)
    }

    @Test
    fun the_result_is_limited_and_ordered_best_first() = runTest {
        val pool = (1..8).map { fresh("$it", rating = it.toDouble()) }.toTypedArray()
        val result = engine(*pool).pick(PickCriteria(), context, limit = 3)
        assertEquals(listOf("Title 8", "Title 7", "Title 6"), result.picks.map { it.candidate.summary.title })
    }

    // ---- ranking and reasons ---------------------------------------------------------------------

    private val today = 20_000L // 2024-10-04

    @Test
    fun a_mood_match_outranks_a_miss_and_is_the_reason_given() = runTest {
        val result = engine(fresh("fits", listOf("35")), fresh("misses", listOf("18"))).pick(PickCriteria(moods = setOf(PickMood.LIGHT)), context)
        assertEquals("Title fits", result.picks.first().candidate.summary.title)
        assertEquals(PickReason.FitsMood(PickMood.LIGHT), result.picks.first().reason)
    }

    @Test
    fun more_like_this_prefers_overlap_and_names_the_anchor() = runTest {
        val anchor = PickAnchor(MediaId.tmdbTv("99"), like = true)
        val history = PickHistory(anchorGenreIds = setOf("80", "18"), anchorTitle = "The Finished Show")
        val result = engine(fresh("close", listOf("80", "18")), fresh("far", listOf("35")), history = history)
            .pick(PickCriteria(anchor = anchor), context)
        assertEquals("Title close", result.picks.first().candidate.summary.title)
        assertEquals(PickReason.LikeAnchor("The Finished Show"), result.picks.first().reason)
    }

    @Test
    fun something_different_pushes_overlap_down() = runTest {
        val anchor = PickAnchor(MediaId.tmdbTv("99"), like = false)
        val history = PickHistory(anchorGenreIds = setOf("80"))
        val result = engine(fresh("same", listOf("80")), fresh("other", listOf("35")), history = history).pick(PickCriteria(anchor = anchor), context)
        assertEquals("Title other", result.picks.first().candidate.summary.title)
    }

    @Test
    fun genres_the_person_rates_high_lift_a_title_and_say_so() = runTest {
        val history = PickHistory(genreRatings = mapOf("878" to 9.0, "10749" to 4.0))
        val result = engine(fresh("scifi", listOf("878")), fresh("romance", listOf("10749")), history = history).pick(PickCriteria(), context)
        assertEquals("Title scifi", result.picks.first().candidate.summary.title)
        assertEquals(PickReason.YouRateTheseGenresHigh("878", 9.0), result.picks.first().reason)
    }

    @Test
    fun a_favorite_in_the_backlog_is_the_reason_when_nothing_else_is_asked() = runTest {
        val result = engine(backlog("1", favorite = true), backlog("2")).pick(PickCriteria(source = PickSource.MY_LIST), context)
        assertEquals(PickReason.YourFavorite, result.picks.first().reason)
    }

    @Test
    fun time_era_and_language_fit_count() = runTest {
        val short = fresh("short", year = 2023).copy(runtimeMinutes = 90, originalLanguage = "es")
        val long = fresh("long", year = 1980).copy(runtimeMinutes = 200, originalLanguage = "fr")
        val history = PickHistory(todayEpochDay = today)
        val criteria = PickCriteria(runtime = PickRuntime.QUICK, era = PickEra.NEW, originalLanguage = "es")
        val result = engine(short, long, history = history).pick(criteria, context)
        assertEquals("Title short", result.picks.first().candidate.summary.title)
        assertTrue(result.picks.first().score > result.picks.last().score)
    }

    @Test
    fun with_nothing_to_go_on_the_reason_is_that_it_fits_what_was_asked() = runTest {
        val result = engine(fresh("1")).pick(PickCriteria(), context)
        assertEquals(PickReason.FitsWhatYouAsked, result.picks.single().reason)
    }

    @Test
    fun ties_break_by_title_so_the_order_is_deterministic() = runTest {
        val a = engine(fresh("2"), fresh("1")).pick(PickCriteria(), context)
        val b = engine(fresh("1"), fresh("2")).pick(PickCriteria(), context)
        assertEquals(ids(a), ids(b))
    }

    @Test
    fun a_liked_genre_lifts_its_title_and_is_the_reason_given() = runTest {
        val history = PickHistory(likedGenres = setOf(GenreKey(MediaType.MOVIE, "10749")))
        val result = engine(fresh("romance", listOf("10749")), fresh("other", listOf("18")), history = history).pick(PickCriteria(), context)

        assertEquals("Title romance", result.picks.first().candidate.summary.title)
        assertEquals(PickReason.LikedGenre, result.picks.first().reason)
    }

    @Test
    fun a_genre_liked_then_muted_never_reaches_the_pool() = runTest {
        val muted = MutedGenres(setOf(GenreKey(MediaType.MOVIE, "27")))
        val history = PickHistory(likedGenres = setOf(GenreKey(MediaType.MOVIE, "27")))
        val result = engine(fresh("horror", listOf("27")), fresh("drama", listOf("18")), muted = muted, history = history).pick(PickCriteria(), context)

        assertEquals(listOf("Title drama"), result.picks.map { it.candidate.summary.title })
    }

    @Test
    fun a_liked_genre_muted_now_gives_no_lift_even_when_a_mood_lifts_the_mute() = runTest {
        // Light reads as comedy (35), so the mood shows a muted comedy. The standing mute still stops the like from counting.
        val muted = MutedGenres(setOf(GenreKey(MediaType.MOVIE, "35")))
        val criteria = PickCriteria(moods = setOf(PickMood.LIGHT))
        val liked = PickHistory(likedGenres = setOf(GenreKey(MediaType.MOVIE, "35")))
        val withLike = engine(fresh("comedy", listOf("35")), muted = muted, history = liked).pick(criteria, context)
        val withoutLike = engine(fresh("comedy", listOf("35")), muted = muted).pick(criteria, context)

        assertEquals(withoutLike.picks.single().score, withLike.picks.single().score)
        assertEquals(PickReason.FitsMood(PickMood.LIGHT), withLike.picks.single().reason)
    }

    // ---- the ranker's output is enforced (ADR 0032) -----------------------------------------------

    private val rulesPool = arrayOf(fresh("1", rating = 9.0), fresh("2", rating = 7.0), fresh("3", rating = 5.0))

    /** A ranker that is the rules' order with [change] applied to it: what a misbehaving model would hand back. */
    @Test
    fun an_anchor_copy_from_tmdb_beats_a_discover_copy_so_the_anchor_credit_survives() = runTest {
        val discover = fresh("1", source = CandidateSource.DISCOVER)
        val recommended = fresh("1", source = CandidateSource.RECOMMENDED)
        val result = PickEngine(
            sources = listOf(PickCandidates { _, _ -> listOf(discover) }, PickCandidates { _, _ -> listOf(recommended) }),
            exclusions = { emptySet() },
            history = { PickHistory() },
            muting = { MutedGenres.NONE },
        ).pick(PickCriteria(anchor = PickAnchor(MediaId.tmdbTv("9"), like = true)), context)
        assertEquals(CandidateSource.RECOMMENDED, result.picks.single().candidate.source)
    }

    @Test
    fun the_title_just_finished_is_never_its_own_more_like_this() = runTest {
        val anchor = MediaId.tmdbMovie("9")
        val result = engine(fresh("1"), PickCandidate(summary("9"), CandidateSource.RECOMMENDED))
            .pick(PickCriteria(anchor = PickAnchor(anchor, like = true)), context)
        assertEquals(listOf("tmdb:movie:1"), ids(result))
    }

    @Test
    fun every_source_mix_returns_picks_and_each_pick_names_its_reason() = runTest {
        val pools = mapOf(
            "backlog" to listOf(backlog("1", favorite = true)),
            "discover" to listOf(fresh("2")),
            "recommended" to listOf(PickCandidate(summary("3"), CandidateSource.RECOMMENDED)),
            "similar" to listOf(PickCandidate(summary("4"), CandidateSource.SIMILAR)),
            "all four" to listOf(backlog("1", favorite = true), fresh("2"), PickCandidate(summary("3"), CandidateSource.RECOMMENDED), PickCandidate(summary("4"), CandidateSource.SIMILAR)),
        )
        val criteria = PickCriteria(anchor = PickAnchor(MediaId.tmdbTv("9"), like = true))
        for ((mix, pool) in pools) {
            val result = engine(*pool.toTypedArray()).pick(criteria, context)
            assertTrue(result.picks.isNotEmpty(), "the $mix mix returned no picks")
            assertEquals(pool.size, result.picks.size, "the $mix mix dropped a candidate")
        }
    }

    @Test
    fun each_source_gives_its_pick_the_reason_the_rules_can_show() = runTest {
        val criteria = PickCriteria(anchor = PickAnchor(MediaId.tmdbTv("9"), like = true))
        val result = engine(
            backlog("1", favorite = true),
            fresh("2"),
            PickCandidate(summary("3"), CandidateSource.RECOMMENDED),
            PickCandidate(summary("4"), CandidateSource.SIMILAR),
            history = PickHistory(anchorTitle = "Severance"),
        ).pick(criteria, context)
        val reasons = result.picks.associate { it.candidate.summary.id.toString() to it.reason }
        assertEquals(PickReason.YourFavorite, reasons["tmdb:movie:1"])
        assertEquals(PickReason.FitsWhatYouAsked, reasons["tmdb:movie:2"])
        assertEquals(PickReason.LikeAnchor("Severance"), reasons["tmdb:movie:3"])
        assertEquals(PickReason.LikeAnchor("Severance"), reasons["tmdb:movie:4"])
    }

    private fun hostile(change: (List<RankedPick>) -> List<RankedPick>) = PickRanker { criteria, pool, history ->
        change(RuleBasedRanker().rank(criteria, pool, history))
    }

    private suspend fun rulesPicks(excluded: Set<MediaId> = emptySet()): List<RankedPick> = engine(*rulesPool, excluded = excluded).pick(PickCriteria(), context).picks

    @Test
    fun a_ranker_that_returns_an_excluded_title_gets_the_rules_result_and_it_is_never_shown() = runTest {
        val excluded = setOf(MediaId.tmdbMovie("9"))
        val excludedPick = RankedPick(fresh("9", rating = 10.0), 99.0, PickReason.YourFavorite)
        val result = engine(*rulesPool, excluded = excluded, ranker = hostile { it + excludedPick }).pick(PickCriteria(), context)

        assertEquals(rulesPicks(excluded), result.picks)
        assertTrue(result.picks.none { it.candidate.id == MediaId.tmdbMovie("9") })
    }

    @Test
    fun a_ranker_that_returns_only_excluded_titles_gets_the_rules_result() = runTest {
        val excludedPick = RankedPick(fresh("9", rating = 10.0), 99.0, PickReason.YourFavorite)
        val result = engine(*rulesPool, excluded = setOf(MediaId.tmdbMovie("9")), ranker = hostile { listOf(excludedPick) })
            .pick(PickCriteria(), context)

        assertEquals(rulesPicks(), result.picks)
    }

    @Test
    fun a_ranker_that_returns_a_title_from_outside_the_pool_gets_the_rules_result() = runTest {
        val foreignPick = RankedPick(fresh("42", rating = 10.0), 99.0, PickReason.YourFavorite)
        val result = engine(*rulesPool, ranker = hostile { it + foreignPick }).pick(PickCriteria(), context)

        assertEquals(rulesPicks(), result.picks)
        assertTrue(result.picks.none { it.candidate.summary.title == "Title 42" })
    }

    @Test
    fun a_ranker_that_repeats_a_title_gets_the_rules_result_with_each_title_once() = runTest {
        val result = engine(*rulesPool, ranker = hostile { it + it.first() }).pick(PickCriteria(), context)

        assertEquals(rulesPicks(), result.picks)
        assertEquals(3, result.picks.map { it.candidate.id }.toSet().size)
    }

    @Test
    fun a_ranker_that_throws_gets_the_rules_result_without_an_error() = runTest {
        val throwing = PickRanker { _, _, _ -> error("model crashed") }
        val result = engine(*rulesPool, ranker = throwing).pick(PickCriteria(), context)

        assertEquals(rulesPicks(), result.picks)
    }

    @Test
    fun a_ranker_that_throws_is_reported_once_by_class_name_and_never_by_message() = runTest {
        val reporter = RecordingReporter()
        val throwing = PickRanker { _, _, _ -> error("model crashed with the request text") }
        val result = reported(reporter, throwing, *rulesPool).pick(PickCriteria(), context)

        assertEquals(rulesPicks(), result.picks)
        assertEquals(listOf("IllegalStateException"), reporter.failures)
        assertEquals(0, reporter.timeouts)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun a_ranker_that_times_out_is_reported_as_a_timeout_and_not_as_a_failure() = runTest {
        val reporter = RecordingReporter()
        val slow = PickRanker { _, _, _ ->
            delay(1.hours)
            emptyList()
        }
        val result = reported(reporter, slow, *rulesPool).pick(PickCriteria(), context)

        assertEquals(rulesPicks(), result.picks)
        assertEquals(1, reporter.timeouts)
        assertEquals(emptyList(), reporter.failures)
    }

    @Test
    fun a_ranker_that_answers_reports_nothing() = runTest {
        val reporter = RecordingReporter()
        val result = reported(reporter, hostile { it }, *rulesPool).pick(PickCriteria(), context)

        assertEquals(rulesPicks(), result.picks)
        assertEquals(emptyList(), reporter.failures)
        assertEquals(0, reporter.timeouts)
    }

    @Test
    fun a_caller_cancellation_is_rethrown_and_reports_nothing() = runTest {
        val reporter = RecordingReporter()
        val waiting = PickRanker { _, _, _ -> awaitCancellation() }
        val call = launch { reported(reporter, waiting, *rulesPool).pick(PickCriteria(), context) }
        runCurrent()
        call.cancelAndJoin()

        assertTrue(call.isCancelled)
        assertEquals(emptyList(), reporter.failures)
        assertEquals(0, reporter.timeouts)
        assertEquals(emptyList(), reporter.unusable)
    }

    @Test
    fun a_ranker_that_times_out_on_its_own_falls_back_and_is_reported_as_a_failure() = runTest {
        val reporter = RecordingReporter()
        val own = PickRanker { _, _, _ ->
            withTimeout(1.seconds) { delay(1.hours) }
            emptyList()
        }
        val result = reported(reporter, own, *rulesPool).pick(PickCriteria(), context)

        assertEquals(rulesPicks(), result.picks)
        assertEquals(listOf("TimeoutCancellationException"), reporter.failures)
        assertEquals(0, reporter.timeouts)
    }

    @Test
    fun a_ranker_whose_picks_are_all_unusable_is_reported_by_count_and_the_rules_answer() = runTest {
        val reporter = RecordingReporter()
        val outside = RankedPick(fresh("42", rating = 10.0), 99.0, PickReason.YourFavorite)
        val result = reported(reporter, hostile { listOf(outside, outside) }, *rulesPool).pick(PickCriteria(), context)

        assertEquals(rulesPicks(), result.picks)
        assertEquals(listOf(2), reporter.unusable)
        assertEquals(emptyList(), reporter.failures)
    }

    @Test
    fun a_ranker_that_has_nothing_to_say_is_reported_as_unusable_with_a_count_of_zero() = runTest {
        val reporter = RecordingReporter()
        val result = reported(reporter, hostile { emptyList() }, *rulesPool).pick(PickCriteria(), context)

        assertEquals(rulesPicks(), result.picks)
        assertEquals(listOf(0), reporter.unusable)
    }

    @Test
    fun a_ranker_with_some_usable_picks_is_not_reported_as_unusable() = runTest {
        val reporter = RecordingReporter()
        val outside = RankedPick(fresh("42", rating = 10.0), 99.0, PickReason.YourFavorite)
        val result = reported(reporter, hostile { listOf(outside) + it }, *rulesPool).pick(PickCriteria(), context)

        assertEquals(rulesPicks(), result.picks)
        assertEquals(emptyList(), reporter.unusable)
    }

    @Test
    fun an_empty_pool_is_not_reported_as_unusable() = runTest {
        val reporter = RecordingReporter()
        val result = reported(reporter, hostile { emptyList() }).pick(PickCriteria(), context)

        assertTrue(result.picks.isEmpty())
        assertEquals(emptyList(), reporter.unusable)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun a_ranker_that_is_slow_gets_the_rules_result_after_the_timeout_in_virtual_time() = runTest {
        val slow = PickRanker { _, _, _ ->
            delay(1.hours)
            emptyList()
        }
        val result = engine(*rulesPool, ranker = slow).pick(PickCriteria(), context)

        assertEquals(rulesPicks(), result.picks)
        assertEquals(5_000L, testScheduler.currentTime)
    }

    @Test
    fun a_ranker_may_reorder_the_pool_and_the_limit_still_applies() = runTest {
        val reversed = hostile { it.reversed() }
        val result = engine(*rulesPool, ranker = reversed).pick(PickCriteria(), context, limit = 2)

        assertEquals(listOf("Title 3", "Title 2"), result.picks.map { it.candidate.summary.title })
    }
}
