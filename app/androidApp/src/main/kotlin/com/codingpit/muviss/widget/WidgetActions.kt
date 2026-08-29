package com.codingpit.muviss.widget

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.codingpit.muviss.MainActivity
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.models.EpisodeId
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** The episode a tick or an undo applies to, passed as its string form. */
val EpisodeIdKey = ActionParameters.Key<String>("episodeId")

/** The media id a row tap opens, matching [MainActivity.EXTRA_DEEP_LINK_MEDIA_ID]. */
val DeepLinkMediaIdKey = ActionParameters.Key<String>(MainActivity.EXTRA_DEEP_LINK_MEDIA_ID)

/** Where the "just ticked, offer Undo" marker lives, per widget instance. */
internal val JustTickedKey = stringPreferencesKey("justTickedEpisodeId")

/**
 * Ticks a row's episode straight from the home screen.
 *
 * The write goes through [ProgressApi] like every other surface, so it lands
 * in one transaction with its `episodePlay` row (ADR 0011) and shows up in
 * the app immediately.
 *
 * The Undo marker is written *after* the tick, not before: the repository
 * refreshes the widgets as part of committing (see `SqlDelightProgressRepository`),
 * and that refresh clears the marker. Setting it afterwards is what makes the
 * banner survive its own write, and any later refresh — the next tick
 * elsewhere, the daily reload — clears it again.
 */
class TickAction :
    ActionCallback,
    KoinComponent {

    private val progressApi: ProgressApi by inject()

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val episodeId = parseEpisodeId(parameters[EpisodeIdKey]) ?: return
        progressApi.setEpisodeSeen(episodeId, seen = true)
        updateAppWidgetState(context, glanceId) { it[JustTickedKey] = episodeId.toString() }
        MuvissWidget().update(context, glanceId)
    }
}

/**
 * Takes back a widget tick, both halves of it.
 *
 * `setEpisodeSeen(false)` clears the episode's viewings along with the tick,
 * which is right here and only here: the widget can only ever tick an episode
 * that was *next unseen*, so it had no history to lose. It is the same call
 * the in-app undo snackbar makes.
 */
class UndoTickAction :
    ActionCallback,
    KoinComponent {

    private val progressApi: ProgressApi by inject()

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val episodeId = parseEpisodeId(parameters[EpisodeIdKey]) ?: return
        progressApi.setEpisodeSeen(episodeId, seen = false)
        updateAppWidgetState(context, glanceId) { it.remove(JustTickedKey) }
        MuvissWidget().update(context, glanceId)
    }
}
