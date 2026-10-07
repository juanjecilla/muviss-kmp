package com.codingpit.muviss.notifications

import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NotificationIdsTest {

    @Test
    fun `ids are stable across calls`() {
        val id = MediaId.tmdbTv("1399")
        assertEquals(NotificationIds.forTitle(id), NotificationIds.forTitle(id))
    }

    @Test
    fun `a movie and a show with the same TMDB number get different ids`() {
        assertNotEquals(NotificationIds.forTitle(MediaId.tmdbMovie("603")), NotificationIds.forTitle(MediaId.tmdbTv("603")))
    }

    @Test
    fun `ten thousand real-shaped TMDB ids never collide and never hit the summary`() {
        val ids = (1..5_000).flatMap { listOf(MediaId.tmdbMovie("$it"), MediaId.tmdbTv("$it")) }.map(NotificationIds::forTitle)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(NotificationIds.SUMMARY !in ids)
    }

    @Test
    fun `a non-numeric id stays negative so it cannot shadow a TMDB title or the summary`() {
        assertTrue(NotificationIds.forTitle(MediaId(SourceId.TMDB, MediaType.TV, "tt0944947")) < 0)
    }
}
