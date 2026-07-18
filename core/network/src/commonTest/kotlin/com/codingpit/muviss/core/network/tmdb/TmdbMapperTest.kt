package com.codingpit.muviss.core.network.tmdb

import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TmdbMapperTest {

    @Test
    fun airDateToEpochDay_null_input_is_null() {
        assertNull(TmdbMapper.airDateToEpochDay(null))
    }

    @Test
    fun airDateToEpochDay_malformed_input_is_null() {
        assertNull(TmdbMapper.airDateToEpochDay("not-a-date"))
        assertNull(TmdbMapper.airDateToEpochDay("2024-01"))
        assertNull(TmdbMapper.airDateToEpochDay(""))
    }

    @Test
    fun airDateToEpochDay_unix_epoch_is_zero() {
        assertEquals(0L, TmdbMapper.airDateToEpochDay("1970-01-01"))
    }

    @Test
    fun airDateToEpochDay_past_date_matches_known_epoch_day() {
        // Hand-verified against the standard days-from-civil algorithm.
        assertEquals(19_741L, TmdbMapper.airDateToEpochDay("2024-01-19"))
    }

    @Test
    fun airDateToEpochDay_future_date_matches_known_epoch_day() {
        assertEquals(22_035L, TmdbMapper.airDateToEpochDay("2030-05-01"))
    }

    @Test
    fun airDateToEpochDay_leap_day_parses() {
        assertEquals(19_782L, TmdbMapper.airDateToEpochDay("2024-02-29"))
    }

    @Test
    fun seasonToModel_maps_air_date_to_epoch_day_per_episode() {
        val show = MediaId.tmdbTv("1399")
        val dto = TmdbSeasonDetailDto(
            seasonNumber = 1,
            name = "Season 1",
            episodes = listOf(
                TmdbEpisodeDto(seasonNumber = 1, episodeNumber = 1, name = "Winter Is Coming", airDate = "2011-04-17"),
                TmdbEpisodeDto(seasonNumber = 1, episodeNumber = 2, name = "The Kingsroad", airDate = null),
            ),
        )

        val season = TmdbMapper.seasonToModel(show, dto)

        assertEquals(TmdbMapper.airDateToEpochDay("2011-04-17"), season.episodes[0].airDateEpochDay)
        assertNull(season.episodes[1].airDateEpochDay)
    }

    @Test
    fun seasonToModel_maps_per_episode_runtime_when_tmdb_reports_it() {
        val show = MediaId.tmdbTv("1399")
        val dto = TmdbSeasonDetailDto(
            seasonNumber = 1,
            name = "Season 1",
            episodes = listOf(
                TmdbEpisodeDto(seasonNumber = 1, episodeNumber = 1, name = "Winter Is Coming", runtime = 62),
                TmdbEpisodeDto(seasonNumber = 1, episodeNumber = 2, name = "The Kingsroad", runtime = null),
            ),
        )

        val season = TmdbMapper.seasonToModel(show, dto)

        assertEquals(62, season.episodes[0].runtimeMinutes)
        assertNull(season.episodes[1].runtimeMinutes)
    }

    @Test
    fun discoverResultToSummary_maps_a_movie_row_by_release_date() {
        val dto = TmdbResultDto(id = 603, title = "The Matrix", releaseDate = "1999-03-30", posterPath = "/m.jpg")

        val summary = TmdbMapper.discoverResultToSummary(MediaType.MOVIE, dto)

        assertEquals(MediaId.tmdbMovie("603"), summary.id)
        assertEquals("The Matrix", summary.title)
        assertEquals(1999, summary.year)
        assertEquals(TmdbMapper.IMAGE_BASE + "/m.jpg", summary.posterUrl)
    }

    @Test
    fun discoverResultToSummary_maps_a_tv_row_by_first_air_date() {
        val dto = TmdbResultDto(id = 1399, name = "Game of Thrones", firstAirDate = "2011-04-17")

        val summary = TmdbMapper.discoverResultToSummary(MediaType.TV, dto)

        assertEquals(MediaId.tmdbTv("1399"), summary.id)
        assertEquals("Game of Thrones", summary.title)
        assertEquals(2011, summary.year)
    }

    @Test
    fun movieToDetails_maps_backdrop_at_w780() {
        val dto = TmdbMovieDetailDto(id = 603, title = "The Matrix", backdropPath = "/back.jpg")

        val details = TmdbMapper.movieToDetails(dto)

        assertEquals(TmdbMapper.BACKDROP_BASE + "/back.jpg", details.backdropUrl)
    }

    @Test
    fun tvToDetails_maps_missing_backdrop_to_null() {
        val dto = TmdbTvDetailDto(id = 1399, name = "Game of Thrones")

        val details = TmdbMapper.tvToDetails(dto, seasons = emptyList())

        assertEquals(null, details.backdropUrl)
    }

    @Test
    fun discoverPageToPagedResult_carries_page_and_totalPages_through() {
        val dto = TmdbPageDto(
            page = 2,
            totalPages = 5,
            results = listOf(TmdbResultDto(id = 1, title = "A", releaseDate = "2020-01-01")),
        )

        val result = TmdbMapper.discoverPageToPagedResult(MediaType.MOVIE, dto)

        assertEquals(2, result.page)
        assertEquals(5, result.totalPages)
        assertEquals(1, result.items.size)
        assertTrue(result.hasMore)
    }

    @Test
    fun searchPageToPagedResult_drops_non_movie_tv_rows() {
        val dto = TmdbPageDto(
            page = 1,
            totalPages = 1,
            results = listOf(
                TmdbResultDto(id = 1, mediaType = "movie", title = "A", releaseDate = "2020-01-01"),
                TmdbResultDto(id = 2, mediaType = "person", title = "A person"),
            ),
        )

        val result = TmdbMapper.searchPageToPagedResult(dto)

        assertEquals(1, result.items.size)
        assertEquals(MediaId.tmdbMovie("1"), result.items.single().id)
    }

    @Test
    fun genreListToModels_maps_id_and_name() {
        val dto = TmdbGenreListDto(genres = listOf(TmdbGenreListItemDto(id = 28, name = "Action")))

        val genres = TmdbMapper.genreListToModels(dto)

        assertEquals(listOf(Genre("28", "Action")), genres)
    }

    @Test
    fun watchProvidersToModel_null_region_maps_to_empty() {
        val providers = TmdbMapper.watchProvidersToModel(null)

        assertTrue(providers.isEmpty)
    }

    @Test
    fun findResponseToSummary_movie_type_reads_the_movie_list() {
        val dto = TmdbFindResponseDto(
            movieResults = listOf(TmdbResultDto(id = 603, title = "The Matrix", releaseDate = "1999-03-30")),
            tvResults = listOf(TmdbResultDto(id = 1399, name = "Game of Thrones", firstAirDate = "2011-04-17")),
        )

        val summary = TmdbMapper.findResponseToSummary(dto, MediaType.MOVIE)

        assertEquals(MediaId.tmdbMovie("603"), summary?.id)
    }

    @Test
    fun findResponseToSummary_tv_type_reads_the_tv_list() {
        val dto = TmdbFindResponseDto(tvResults = listOf(TmdbResultDto(id = 1399, name = "Game of Thrones", firstAirDate = "2011-04-17")))

        val summary = TmdbMapper.findResponseToSummary(dto, MediaType.TV)

        assertEquals(MediaId.tmdbTv("1399"), summary?.id)
    }

    @Test
    fun findResponseToSummary_null_type_falls_back_movie_then_tv() {
        val tvOnly = TmdbFindResponseDto(tvResults = listOf(TmdbResultDto(id = 1399, name = "Game of Thrones", firstAirDate = "2011-04-17")))
        assertEquals(MediaId.tmdbTv("1399"), TmdbMapper.findResponseToSummary(tvOnly, null)?.id)

        val movieOnly = TmdbFindResponseDto(movieResults = listOf(TmdbResultDto(id = 603, title = "The Matrix", releaseDate = "1999-03-30")))
        assertEquals(MediaId.tmdbMovie("603"), TmdbMapper.findResponseToSummary(movieOnly, null)?.id)
    }

    @Test
    fun findResponseToSummary_no_match_is_null() {
        assertNull(TmdbMapper.findResponseToSummary(TmdbFindResponseDto(), MediaType.MOVIE))
        assertNull(TmdbMapper.findResponseToSummary(TmdbFindResponseDto(), null))
    }

    @Test
    fun watchProvidersToModel_maps_each_offer_type_and_logo_url() {
        val dto = TmdbWatchProviderRegionDto(
            flatrate = listOf(TmdbWatchProviderDto(providerId = 8, providerName = "Netflix", logoPath = "/n.jpg")),
            rent = listOf(TmdbWatchProviderDto(providerId = 2, providerName = "Apple TV")),
            buy = emptyList(),
        )

        val providers = TmdbMapper.watchProvidersToModel(dto)

        assertEquals(1, providers.flatrate.size)
        assertEquals("Netflix", providers.flatrate.single().name)
        assertEquals(TmdbMapper.LOGO_BASE + "/n.jpg", providers.flatrate.single().logoUrl)
        assertEquals(1, providers.rent.size)
        assertNull(providers.rent.single().logoUrl)
        assertTrue(providers.buy.isEmpty())
        assertTrue(!providers.isEmpty)
    }
}
