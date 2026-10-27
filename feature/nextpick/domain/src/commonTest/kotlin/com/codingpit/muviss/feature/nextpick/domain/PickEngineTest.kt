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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PickEngineTest {

    private fun summary(id: String, genres: List<String> = emptyList(), type: MediaType = MediaType.MOVIE, year: Int? = null, rating: Double? = null) = MediaSummary(if (type == MediaType.MOVIE) MediaId.tmdbMovie(id) else MediaId.tmdbTv(id), "Title $id", year, genreIds = genres, rating = rating)

    private fun backlog(id: String, genres: List<String> = emptyList(), status: WatchStatus = WatchStatus.NOT_STARTED, favorite: Boolean = false) = PickCandidate(summary(id, genres), CandidateSource.BACKLOG, libraryStatus = status, favorite = favorite)

    private fun fresh(id: String, genres: List<String> = emptyList(), source: CandidateSource = CandidateSource.DISCOVER, year: Int? = null, rating: Double? = null) = PickCandidate(summary(id, genres, year = year, rating = rating), source)

    private fun engine(
        vararg pool: PickCandidate,
        excluded: Set<MediaId> = emptySet(),
        muted: MutedGenres = MutedGenres.NONE,
        history: PickHistory = PickHistory(),
    ) = PickEngine(
        sources = listOf(PickCandidates { _, _ -> pool.toList() }),
        exclusions = { excluded },
        history = { history },
        muting = { muted },
    )

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
}
