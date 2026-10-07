package com.codingpit.muviss.storeshot

import com.codingpit.muviss.core.network.MetadataProvider
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.ProductionStatus
import com.codingpit.muviss.models.Season
import com.codingpit.muviss.models.SourceId

/**
 * The library the store screenshots show (EPIC 33, #76).
 *
 * Every title, synopsis and poster here is invented. Real TMDB titles and
 * posters would make the screenshots marketing material built on someone
 * else's artwork, which TMDB's terms do not cover and Play's IP policy can
 * reject; a fictional catalogue sidesteps both and never goes stale.
 *
 * Air dates are relative to [today] so "Watch next" and "Upcoming" look the
 * same whenever the screenshots are regenerated.
 */
internal class StoreShotCatalog(private val today: Long) {

    class Title(
        val summary: MediaSummary,
        val genres: List<String>,
        val runtimeMinutes: Int,
        val status: ProductionStatus,
        /** Episodes per season; the last [unairedInLastSeason] of the last season air in the future. */
        val seasonSizes: List<Int> = emptyList(),
        val unairedInLastSeason: Int = 0,
    ) {
        val id: MediaId get() = summary.id
    }

    private var nextId = 9_000

    @Suppress("LongParameterList") // A catalogue row, read as one line per title.
    private fun tv(title: String, year: Int, overview: String, genres: List<String>, status: ProductionStatus, seasons: List<Int>, unaired: Int = 0, runtime: Int = 45) = Title(MediaSummary(MediaId.tmdbTv((nextId++).toString()), title, year, posterUrl(title), overview, 8.1), genres, runtime, status, seasons, unaired)

    private fun movie(title: String, year: Int, overview: String, genres: List<String>, runtime: Int) = Title(MediaSummary(MediaId.tmdbMovie((nextId++).toString()), title, year, posterUrl(title), overview, 7.6), genres, runtime, ProductionStatus.RELEASED)

    // --- the library -------------------------------------------------------------
    val lighthouse = tv("The Lighthouse Keepers", 2024, "Three siblings inherit a lighthouse on a storm-battered island, and the logbook their grandmother kept for forty years.", listOf("Drama", "Mystery"), ProductionStatus.RETURNING, listOf(8, 10), unaired = 4)
    val northbound = tv("Northbound", 2023, "A night train crosses the continent in eight days. Every passenger is running from something.", listOf("Thriller", "Drama"), ProductionStatus.RETURNING, listOf(6, 6, 8), unaired = 2)
    val orchard = tv("The Quiet Orchard", 2025, "A retired botanist and her neighbour try to save the last apple orchard in the valley.", listOf("Comedy", "Drama"), ProductionStatus.RETURNING, listOf(10), unaired = 3, runtime = 30)
    val paperMoons = tv("Paper Moons", 2021, "An animator in 1960s Lisbon draws the stories her city is not allowed to tell.", listOf("Animation", "History"), ProductionStatus.RETURNING, listOf(8, 8))
    val glassHarbor = tv("Glass Harbor", 2019, "Divers, smugglers and one stubborn harbourmaster, over three summers on a glassmaking island.", listOf("Crime", "Drama"), ProductionStatus.ENDED, listOf(10, 10, 8))
    val saltStatic = tv("Salt & Static", 2025, "A pirate radio station broadcasts from a decommissioned sea fort.", listOf("Comedy", "Music"), ProductionStatus.RETURNING, listOf(8))
    val copperSky = movie("Copper Sky", 2022, "A crop-duster pilot and a runaway ostrich cross three states in a borrowed plane.", listOf("Adventure", "Comedy"), 104)
    val smallHours = movie("A Map of Small Hours", 2020, "Two insomniacs map their city between 2 and 5 a.m.", listOf("Romance", "Drama"), 98)
    val nightFerry = movie("Night Ferry", 2023, "The last crossing of the season, a missing cargo manifest, and a captain with nothing to lose.", listOf("Thriller", "Mystery"), 117)
    val winterSignal = movie("Winter Signal", 2024, "An Antarctic radio operator hears a voice that should not be there.", listOf("Science Fiction", "Thriller"), 109)

    // --- the triage deck and Discover: titles the library does not have yet ---
    val longWeekend = movie("The Long Weekend", 2025, "Five old friends, one rented villa, and a secret nobody planned to share.", listOf("Comedy", "Drama"), 101)
    val elsewhere = movie("Elsewhere, Tomorrow", 2024, "A cartographer is hired to map a town that appears on no map.", listOf("Fantasy", "Mystery"), 112)
    val ironGarden = tv("The Iron Garden", 2025, "A gardener at a royal palace becomes the queen's most unlikely adviser.", listOf("Drama", "History"), ProductionStatus.RETURNING, listOf(8))
    val riverDistrict = tv("River District", 2024, "Two detectives, one river, and every secret it has carried downstream.", listOf("Crime", "Mystery"), ProductionStatus.RETURNING, listOf(10, 6), unaired = 2)
    val tinyGiants = tv("Tiny Giants", 2023, "Nature documentary series about the smallest animals doing the biggest things.", listOf("Documentary"), ProductionStatus.ENDED, listOf(6), runtime = 50)
    val lastLetter = movie("The Last Letter Home", 2021, "A postwoman delivers a letter forty years late.", listOf("Drama", "Romance"), 106)
    val moonbase = movie("Moonbase Diner", 2025, "The only diner on the Moon has one cook, two robots and a very long queue.", listOf("Comedy", "Science Fiction"), 94)

    val library = listOf(lighthouse, northbound, orchard, paperMoons, glassHarbor, saltStatic, copperSky, smallHours, nightFerry, winterSignal)
    val fresh = listOf(ironGarden, longWeekend, riverDistrict, elsewhere, tinyGiants, lastLetter, moonbase)
    private val all = (library + fresh).associateBy { it.id }

    fun details(id: MediaId): MediaDetails {
        val title = all.getValue(id)
        return MediaDetails(
            summary = title.summary,
            genres = title.genres,
            runtimeMinutes = title.runtimeMinutes,
            productionStatus = title.status,
            seasons = seasonsOf(title),
        )
    }

    /**
     * Weekly episodes counted back from [today]: the last aired one aired this
     * week, and the unaired tail of the final season continues weekly after it.
     */
    private fun seasonsOf(title: Title): List<Season> {
        if (title.summary.type != MediaType.TV) return emptyList()
        val total = title.seasonSizes.sum()
        val airedCount = total - title.unairedInLastSeason
        var index = 0
        return title.seasonSizes.mapIndexed { s, size ->
            val number = s + 1
            Season(
                number = number,
                name = "Season $number",
                episodes = (1..size).map { e ->
                    val weeksFromLastAired = index - (airedCount - 1)
                    index++
                    Episode(
                        id = EpisodeId(title.id, number, e),
                        seasonNumber = number,
                        episodeNumber = e,
                        name = EPISODE_NAMES[(index + number) % EPISODE_NAMES.size],
                        airDateEpochDay = today - 3 + weeksFromLastAired * 7L,
                        runtimeMinutes = title.runtimeMinutes,
                    )
                },
            )
        }
    }

    val genres = listOf("Drama", "Comedy", "Thriller", "Mystery", "Documentary", "Science Fiction").mapIndexed { i, name -> Genre("g$i", name) }

    private fun posterUrl(title: String) = "https://image.tmdb.org/t/p/w342/$POSTER_PREFIX${title.lowercase().filter(Char::isLetterOrDigit)}.png"

    /** The catalogue as the app's only metadata source: nothing reaches TMDB. */
    fun provider(): MetadataProvider = object : MetadataProvider {
        override val source: SourceId = SourceId.TMDB
        override suspend fun search(query: String, page: Int) = PagedResult(all.values.filter { it.summary.title.contains(query, ignoreCase = true) }.map { it.summary }, page = 1, totalPages = 1)
        override suspend fun trending(): List<MediaSummary> = (fresh + library.take(3)).map { it.summary }
        override suspend fun details(id: MediaId): MediaDetails = this@StoreShotCatalog.details(id)
        override suspend fun discover(type: MediaType, page: Int, genreId: String?) = PagedResult(if (page == 1) fresh.filter { it.summary.type == type }.map { it.summary } else emptyList(), page = page, totalPages = 1)
        override suspend fun genres(type: MediaType): List<Genre> = genres
        override suspend fun recommendations(id: MediaId, page: Int) = PagedResult(fresh.take(4).map { it.summary }, page = 1, totalPages = 1)
        override suspend fun similar(id: MediaId, page: Int) = PagedResult(fresh.drop(3).map { it.summary }, page = 1, totalPages = 1)
    }

    companion object {
        const val POSTER_PREFIX = "storeshot-"
        private val EPISODE_NAMES = listOf(
            "The Logbook", "Low Tide", "Signal Fires", "What the Gulls Saw", "Fog Bell", "The Keeper's Daughter",
            "Northern Lights", "Undertow", "A Lamp in the Window", "Spring Tide", "The Long Watch", "Beacon",
        )
    }
}
