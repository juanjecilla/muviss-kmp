package com.codingpit.muviss

import com.codingpit.muviss.app.shared.generated.resources.Res
import com.codingpit.muviss.app.shared.generated.resources.notification_episode_out
import com.codingpit.muviss.app.shared.generated.resources.notification_now_available
import com.codingpit.muviss.app.shared.generated.resources.notification_shows_with_new_episodes
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

/**
 * New-episode notification copy for the hosts that post through Kotlin —
 * desktop's tray and iOS (EPIC 31, #222). Android has its own in
 * `res/values*` because its notifier reads Android resources. The same words
 * in all three: the title carries the show, the body says what is out.
 */
interface NotificationText {
    suspend fun episodeOut(episodeLabel: String): String

    suspend fun nowAvailable(): String

    suspend fun showsWithNewEpisodes(count: Int): String
}

/** [NotificationText] in the device's language, from `:app:shared`'s resources. */
object ResourceNotificationText : NotificationText {
    override suspend fun episodeOut(episodeLabel: String): String = getString(Res.string.notification_episode_out, episodeLabel)

    override suspend fun nowAvailable(): String = getString(Res.string.notification_now_available)

    override suspend fun showsWithNewEpisodes(count: Int): String = getPluralString(Res.plurals.notification_shows_with_new_episodes, count, count)
}
