package com.codingpit.muviss.core.network.tmdb

import com.codingpit.muviss.core.network.muvissJson
import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** List rows carry `genre_ids` (checked live on search, trending and discover); Muted Genres filter on them. */
class TmdbGenreIdsTest {
    @Test
    fun a_discover_row_keeps_its_genre_ids() {
        val dto = TmdbResultDto(id = 1489, name = "Jimmy Kimmel Live!", genreIds = listOf(10767, 35))

        val summary = TmdbMapper.discoverResultToSummary(MediaType.TV, dto)

        assertEquals(listOf("10767", "35"), summary.genreIds)
    }

    @Test
    fun a_row_without_genre_ids_maps_to_an_empty_list() {
        val summary = TmdbMapper.discoverResultToSummary(MediaType.TV, TmdbResultDto(id = 1, name = "The Oscars"))

        assertEquals(emptyList(), summary.genreIds)
    }

    @Test
    fun genre_ids_parse_from_tmdbs_wire_name() {
        val dto = muvissJson.decodeFromString<TmdbResultDto>("""{"id":1,"name":"X","genre_ids":[35,10767]}""")

        assertEquals(listOf(35, 10767), dto.genreIds)
    }

    @Test
    fun a_search_result_keeps_its_genre_ids() = runTest {
        val body = """{"page":1,"total_pages":1,"results":[{"id":1,"media_type":"tv","name":"Late Show","genre_ids":[35,10767]}]}"""
        val fixture = ProviderFixture(handler = { respondJson(body) })

        val result = fixture.provider.search("late show")

        assertEquals(listOf("35", "10767"), result.items.single().genreIds)
    }

    @Test
    fun tv_details_keep_genre_ids_next_to_the_names() {
        val dto = muvissJson.decodeFromString<TmdbTvDetailDto>("""{"id":1489,"name":"Kimmel","genres":[{"id":10767,"name":"Talk"},{"id":35,"name":"Comedy"}]}""")

        val details = TmdbMapper.tvToDetails(dto, seasons = emptyList())

        assertEquals(listOf("Talk", "Comedy"), details.genres)
        assertEquals(listOf("10767", "35"), details.summary.genreIds)
    }
}
