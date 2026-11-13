package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.epochDayOfCivil
import com.codingpit.muviss.core.common.epochMsAtStartOfDay
import com.codingpit.muviss.feature.nextpick.api.PickAnchor
import com.codingpit.muviss.feature.nextpick.api.PickCriteria
import com.codingpit.muviss.feature.nextpick.api.PickMood
import com.codingpit.muviss.feature.nextpick.domain.PickContext
import com.codingpit.muviss.feature.nextpick.domain.PickReason
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.GenreKey
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MutedGenres
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Library titles reach Muted Genres and mood matching through their mapped genre ids (#547, ADR 0031, ADR 0032). */
class LibraryTitlesInPickTest {

    @Test
    fun muted_talk_show_from_the_library_is_not_offered() = runTest {
        val engine = libraryEngine(
            library = listOf(
                libraryTitle("1", "Late Night Show", MediaType.TV, genres = listOf("Talk")),
                libraryTitle("2", "Drama Show", MediaType.TV, genres = listOf("Drama")),
            ),
            muted = MutedGenres(setOf(GenreKey(MediaType.TV, "10767"))),
        )

        val titles = engine.pick(PickCriteria(), PickContext(online = true)).picks.map { it.candidate.summary.title }

        assertEquals(listOf("Drama Show"), titles)
    }

    @Test
    fun mood_match_fires_for_a_library_title() = runTest {
        val engine = libraryEngine(
            library = listOf(
                libraryTitle("1", "Comedy Night", genres = listOf("Comedy")),
                libraryTitle("2", "Drama Film", genres = listOf("Drama")),
            ),
        )

        val top = engine.pick(PickCriteria(moods = setOf(PickMood.LIGHT)), PickContext(online = true)).picks.first()

        assertEquals("Comedy Night", top.candidate.summary.title)
        assertEquals(PickReason.FitsMood(PickMood.LIGHT), top.reason)
    }

    @Test
    fun unmapped_genre_name_is_tolerated_and_the_title_is_still_offered() = runTest {
        val engine = libraryEngine(
            library = listOf(libraryTitle("1", "Odd Film", genres = listOf("Not A Real Genre"))),
            live = { error("catalogue unavailable") },
        )

        val picks = engine.pick(PickCriteria(), PickContext(online = true)).picks

        assertEquals(listOf("Odd Film"), picks.map { it.candidate.summary.title })
        assertTrue(picks.single().candidate.summary.genreIds.isEmpty())
    }

    @Test
    fun spanish_stored_name_is_muted_offline_through_the_bundled_names() = runTest {
        val engine = libraryEngine(
            library = listOf(
                libraryTitle("1", "Sci Fi Film", genres = listOf("Ciencia ficción")),
                libraryTitle("2", "Drama Film", genres = listOf("Drama")),
            ),
            muted = MutedGenres(setOf(GenreKey(MediaType.MOVIE, "878"))),
            language = "en-US",
            online = false,
        )

        val titles = engine.pick(PickCriteria(), PickContext(online = false)).picks.map { it.candidate.summary.title }

        assertEquals(listOf("Drama Film"), titles)
    }

    @Test
    fun english_stored_name_is_muted_while_the_live_catalogue_is_spanish() = runTest {
        val spanishLive: suspend (MediaType) -> List<Genre> = { listOf(Genre("35", "Comedia")) }
        val engine = libraryEngine(
            library = listOf(
                libraryTitle("1", "Comedy Night", genres = listOf("Comedy")),
                libraryTitle("2", "Drama Film", genres = listOf("Drama")),
            ),
            muted = MutedGenres(setOf(GenreKey(MediaType.MOVIE, "35"))),
            live = spanishLive,
            language = "es-ES",
        )

        val titles = engine.pick(PickCriteria(), PickContext(online = true)).picks.map { it.candidate.summary.title }

        assertEquals(listOf("Drama Film"), titles)
    }

    @Test
    fun same_name_maps_per_type_so_a_movie_is_not_muted_by_a_tv_genre() = runTest {
        val engine = libraryEngine(
            library = listOf(libraryTitle("1", "Adventure Film", genres = listOf("Action & Adventure"))),
            muted = MutedGenres(setOf(GenreKey(MediaType.TV, "10759"))),
        )

        val titles = engine.pick(PickCriteria(), PickContext(online = false)).picks.map { it.candidate.summary.title }

        assertEquals(listOf("Adventure Film"), titles)
    }

    @Test
    fun library_ratings_average_per_mapped_genre_id_for_genre_affinity() = runTest {
        val library = FakeLibrary(
            listOf(
                libraryTitle("1", "A", genres = listOf("Comedy"), rating = 8),
                libraryTitle("2", "B", genres = listOf("Comedy"), rating = 6),
                libraryTitle("3", "C", genres = listOf("Drama")),
            ),
        )
        val genreIds = LibraryGenreIds(liveCatalogue = noLiveCatalogue, language = { "en-US" })

        val history = CollectionHistoryReader(FixedClock, { library }, connectivity(true), genreIds).read(PickCriteria())

        assertEquals(7.0, history.genreRatings["35"])
        assertEquals(null, history.genreRatings["18"])
    }

    @Test
    fun the_anchors_title_and_genres_are_read_from_the_library_for_its_overlap() = runTest {
        val anchor = libraryTitle("9", "Finished", genres = listOf("Comedy", "Drama"))
        val library = FakeLibrary(listOf(anchor))
        val genreIds = LibraryGenreIds(liveCatalogue = noLiveCatalogue, language = { "en-US" })

        val history = CollectionHistoryReader(FixedClock, { library }, connectivity(true), genreIds)
            .read(PickCriteria(anchor = PickAnchor(anchor.mediaId, like = true)))

        assertEquals("Finished", history.anchorTitle)
        assertEquals(setOf("35", "18"), history.anchorGenreIds)
    }

    /**
     * Two titles added on the same evening, 90 and 89 of the person's days ago. Their day is the local one, so a
     * title added at 22:00 at UTC-5 is the day it was added to them, not the next UTC day, and the rule reads
     * the same days the list shows (#756). The next-UTC-day title is the one the old UTC math got a day wrong.
     */
    @Test
    fun a_title_added_late_at_night_counts_its_days_in_the_persons_zone() = runTest {
        val clock = zone(-5 * HOUR, nowLocal = atLocal(TODAY, localHour = 12, offsetMs = -5 * HOUR))
        val ninetyDaysAgo = atLocal(TODAY - 90, localHour = 22, offsetMs = -5 * HOUR)
        val eightyNineDaysAgo = atLocal(TODAY - 89, localHour = 22, offsetMs = -5 * HOUR)
        val engine = libraryEngineOn(
            clock,
            library = listOf(
                libraryTitle("1", "Ninety", genres = emptyList()).copy(addedAtEpochMs = ninetyDaysAgo),
                libraryTitle("2", "Eighty Nine", genres = emptyList()).copy(addedAtEpochMs = eightyNineDaysAgo),
            ),
        )

        val reasons = engine.pick(PickCriteria(), PickContext(online = true)).picks.associate { it.candidate.summary.title to it.reason }

        assertEquals(PickReason.LongInYourList, reasons.getValue("Ninety"))
        assertEquals(PickReason.FitsWhatYouAsked, reasons.getValue("Eighty Nine"))
    }

    /**
     * Berlin-like: +2 in August, +1 in November, with the change on 25 Oct. A title added at 00:30 on 12 Aug is
     * 89 local days old, but its UTC day is 11 Aug and would read as 90. The offset is read at the add, not "now".
     */
    @Test
    fun the_days_are_read_at_the_offset_in_force_at_each_timestamp_across_a_dst_change() = runTest {
        val dstChange = epochMsAtStartOfDay(epochDayOfCivil(2026, 10, 25)) + HOUR
        val berlin = object : AppClock {
            override fun nowEpochMs(): Long = atLocal(TODAY, localHour = 12, offsetMs = HOUR)
            override fun utcOffsetMsAt(epochMs: Long): Long = if (epochMs < dstChange) 2 * HOUR else HOUR
        }
        val addedOn12Aug = atLocal(epochDayOfCivil(2026, 8, 12), localHour = 0, offsetMs = 2 * HOUR) + 30 * MINUTE
        val addedOn11Aug = atLocal(epochDayOfCivil(2026, 8, 11), localHour = 0, offsetMs = 2 * HOUR) + 30 * MINUTE
        val engine = libraryEngineOn(
            berlin,
            library = listOf(
                libraryTitle("1", "Eighty Nine", genres = emptyList()).copy(addedAtEpochMs = addedOn12Aug),
                libraryTitle("2", "Ninety", genres = emptyList()).copy(addedAtEpochMs = addedOn11Aug),
            ),
        )

        val reasons = engine.pick(PickCriteria(), PickContext(online = true)).picks.associate { it.candidate.summary.title to it.reason }

        assertEquals(PickReason.FitsWhatYouAsked, reasons.getValue("Eighty Nine"))
        assertEquals(PickReason.LongInYourList, reasons.getValue("Ninety"))
    }

    private fun zone(utcOffsetMs: Long, nowLocal: Long) = object : AppClock {
        override fun nowEpochMs(): Long = nowLocal
        override fun utcOffsetMsAt(epochMs: Long): Long = utcOffsetMs
    }

    /** The UTC instant at which the local day [epochDay] is at [localHour] in a zone at [offsetMs]. */
    private fun atLocal(epochDay: Long, localHour: Long, offsetMs: Long): Long = epochMsAtStartOfDay(epochDay) + localHour * HOUR - offsetMs

    private companion object {
        val TODAY = epochDayOfCivil(2026, 11, 9)
        const val HOUR = 3_600_000L
        const val MINUTE = 60_000L
    }
}
