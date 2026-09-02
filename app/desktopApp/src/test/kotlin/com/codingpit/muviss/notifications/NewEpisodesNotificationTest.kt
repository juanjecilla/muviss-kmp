package com.codingpit.muviss.notifications

import com.codingpit.muviss.feature.collection.api.NewEpisodesResult
import com.codingpit.muviss.models.MediaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The collapse into a single tray balloon, which is where desktop deliberately
 * diverges from Android's one-notification-per-show plus group summary.
 */
class NewEpisodesNotificationTest {

    private fun result(title: String, label: String?) = NewEpisodesResult(
        mediaId = MediaId.tmdbTv(title.hashCode().toString()),
        title = title,
        newEpisodeCount = 1,
        latestEpisodeLabel = label,
    )

    @Test
    fun `nothing new is nothing to say`() {
        // Null rather than an empty notification, so the caller has no branch
        // of its own to get wrong.
        assertNull(newEpisodesNotification(emptyList()))
    }

    @Test
    fun `one show puts its name in the title and the episode in the body`() {
        val notification = newEpisodesNotification(listOf(result("Severance", "S02E05")))

        assertEquals("Severance", notification?.title)
        // Same wording as the Android notifier's contentText(), whose title
        // likewise already carries the show's name.
        assertEquals("S02E05 is out", notification?.message)
    }

    @Test
    fun `a movie has no episode label to name`() {
        val notification = newEpisodesNotification(listOf(result("Dune", null)))

        assertEquals("Now available", notification?.message)
    }

    @Test
    fun `several shows collapse into one balloon that names them`() {
        val notification = newEpisodesNotification(
            listOf(result("Severance", "S02E05"), result("Andor", "S02E01"), result("Dune", null)),
        )

        // A tray balloon has no shade and no grouping, so three popups would
        // overwrite each other. One, and the app carries the detail.
        assertEquals("3 shows have new episodes", notification?.title)
        assertEquals("Severance, Andor, Dune", notification?.message)
    }
}
