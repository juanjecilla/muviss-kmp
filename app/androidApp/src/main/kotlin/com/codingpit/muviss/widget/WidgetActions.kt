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
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** The episode a tick or an undo applies to, passed as its string form. */
val EpisodeIdKey = ActionParameters.Key<String>("episodeId")

/** The media id a row tap opens, matching [MainActivity.EXTRA_DEEP_LINK_MEDIA_ID]. */
val DeepLinkMediaIdKey = ActionParameters.Key<String>(MainActivity.EXTRA_DEEP_LINK_MEDIA_ID)

/** Set on the widget's empty-state tap, matching [MainActivity.EXTRA_OPEN_NEXT_PICK] (#700). */
val OpenNextPickKey = ActionParameters.Key<Boolean>(MainActivity.EXTRA_OPEN_NEXT_PICK)

/** Where the "just ticked, offer Undo" marker lives, per widget instance. */
internal val JustTickedKey = stringPreferencesKey("justTickedEpisodeId")

/**
 * The just-ticked title, kept with the marker (#888). A tick can take its title out of Watch Next, and the widget
 * then draws that title's row from this rather than from the shared list, until the marker clears.
 */
internal val JustTickedTitleKey = stringPreferencesKey("justTickedTitle")

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
        // Read before the write: the tick may take the title out of Watch Next, and then it is no longer listed.
        val title = progressApi.observeWatchNext().first().firstOrNull { it.mediaId == episodeId.show }?.title
        // The previous marker is read before the write too: the write's refresh clears it (see GlanceWidgetRefresher).
        var markedEpisode: String? = null
        var markedTitle: String? = null
        updateAppWidgetState(context, glanceId) {
            markedEpisode = it[JustTickedKey]
            markedTitle = it[JustTickedTitleKey]
        }
        progressApi.setEpisodeSeen(episodeId, seen = true)
        val keptTitle = rememberedTickTitle(episodeId.toString(), title, markedEpisode, markedTitle)
        updateAppWidgetState(context, glanceId) {
            it[JustTickedKey] = episodeId.toString()
            if (keptTitle != null) it[JustTickedTitleKey] = keptTitle else it.remove(JustTickedTitleKey)
        }
        MuvissWidget().update(context, glanceId)
    }
}

/**
 * The title the Undo row keeps for a tick (#888).
 *
 * A repeated tick of the episode the marker already names finds no Watch Next item, because the first tick took
 * its title out of the list. It then keeps the title the marker remembers rather than dropping it, which would
 * remove the Undo row. A tick of a different episode keeps only the title it found, so a stale title never lands on
 * another show's marker. iOS's `IosWidgetBridge.tick` applies the same rule.
 */
internal fun rememberedTickTitle(
    episodeId: String,
    title: String?,
    markedEpisodeId: String?,
    markedTitle: String?,
): String? = title ?: markedTitle.takeIf { markedEpisodeId == episodeId }

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
        updateAppWidgetState(context, glanceId) {
            it.remove(JustTickedKey)
            it.remove(JustTickedTitleKey)
        }
        MuvissWidget().update(context, glanceId)
    }
}
