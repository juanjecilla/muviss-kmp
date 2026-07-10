package com.codingpit.muviss.core.network.tmdb

import com.codingpit.muviss.models.MediaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
