package com.codingpit.muviss.appfunctions

import com.codingpit.muviss.feature.progress.api.WatchNextItem
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppFunctionMappingTest {

    private val show = MediaId.tmdbTv("1399")

    @Test
    fun episode_label_reads_season_number_and_name() {
        val episode = Episode(EpisodeId(show, 1, 2), seasonNumber = 1, episodeNumber = 2, name = "The Kingsroad")
        assertEquals("S1E2 The Kingsroad", episodeLabel(episode))
    }

    @Test
    fun a_watch_next_title_with_nothing_left_to_tick_has_no_episode_fields() {
        val item = WatchNextItem(mediaId = show, title = "Game of Thrones", posterUrl = null, nextEpisode = null, seenCount = 3, airedCount = 3)
        val entry = item.toEntry()
        assertEquals("tmdb:tv:1399", entry.mediaId)
        assertNull(entry.nextEpisodeId)
        assertNull(entry.nextEpisodeLabel)
        assertEquals(3, entry.seenCount)
    }

    @Test
    fun a_watch_next_entry_carries_the_episode_id_to_tick() {
        val episode = Episode(EpisodeId(show, 1, 2), seasonNumber = 1, episodeNumber = 2, name = "The Kingsroad")
        val item = WatchNextItem(mediaId = show, title = "Game of Thrones", posterUrl = null, nextEpisode = episode)
        assertEquals("tmdb:tv:1399/1/2", item.toEntry().nextEpisodeId)
    }

    @Test
    fun agent_names_are_lower_case_for_media_types() {
        assertEquals("movie", MediaType.MOVIE.agentName())
        assertEquals("tv", MediaType.TV.agentName())
    }
}
