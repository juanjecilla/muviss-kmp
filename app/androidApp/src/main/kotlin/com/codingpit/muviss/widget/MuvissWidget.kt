package com.codingpit.muviss.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.codingpit.muviss.MainActivity
import com.codingpit.muviss.R
import com.codingpit.muviss.core.designsystem.theme.muvissColorScheme
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.models.EpisodeId
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The "Watch next" home-screen widget (EPIC 22): the shows in progress, each
 * with its next unseen aired episode and a one-tap tick.
 *
 * A Glance receiver runs inside the app's own process, so it resolves its
 * dependencies from the global Koin started by `MuvissApplication.onCreate`,
 * exactly as `NewEpisodesWorker` does — no custom factory, no second graph.
 *
 * The list comes from [ProgressApi.observeWatchNext], the same call the
 * Progress tab makes, so the widget cannot drift from the screen it mirrors.
 * It reads a single value rather than collecting: a widget is redrawn by a
 * push (see `GlanceWidgetRefresher`), never by holding a subscription open in
 * a process the system is free to kill.
 */
class MuvissWidget :
    GlanceAppWidget(),
    KoinComponent {

    private val progressApi: ProgressApi by inject()

    override val stateDefinition = PreferencesGlanceStateDefinition

    /**
     * `Exact` rather than the default `Single`: under `Single`, `LocalSize`
     * reports the widget's *minimum* size no matter how large the person has
     * actually made it, so [WidgetSize.forHeightDp] would answer `SMALL`
     * forever and a five-row widget would draw one row.
     */
    override val sizeMode = SizeMode.Exact

    /**
     * Both the watch-next list and the Undo marker are read *inside* the
     * composition, and that is not a style choice.
     *
     * `provideContent` composes and then suspends for the lifetime of the
     * session, so a later `update()` recomposes the composition it already
     * has — it does not re-enter this function. Anything captured out here
     * before `provideContent` is therefore frozen for as long as the session
     * lives, which showed up as a widget that wrote the tick and then went on
     * displaying the episode it had just ticked. Read as state, the same
     * `Flow` that drives the Progress tab drives the widget, and the row
     * advances on its own.
     */
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val items by progressApi.observeWatchNext().collectAsState(initial = emptyList())
            val justTicked = parseEpisodeId(currentState<Preferences>()[JustTickedKey])

            GlanceTheme(colors = MuvissGlanceColors) {
                val size = WidgetSize.forHeightDp(LocalSize.current.height.value.toInt())
                WidgetBody(watchNextWidgetUi(items, size, justTicked))
            }
        }
    }

    private companion object {
        /**
         * The app's own amber, not the system's wallpaper colours: a widget
         * that adopts Material You stops being recognisably Muviss on a home
         * screen full of other widgets, and the iOS widget has no equivalent
         * to adopt, so the two platforms would stop matching each other too.
         */
        val MuvissGlanceColors = ColorProviders(
            light = muvissColorScheme(darkTheme = false),
            dark = muvissColorScheme(darkTheme = true),
        )
    }
}

/** Registers [MuvissWidget] with the app-widget host. See `AndroidManifest.xml`. */
class MuvissWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MuvissWidget()
}

@Composable
private fun WidgetBody(ui: WatchNextWidgetUi) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.background)
            .cornerRadius(16.dp)
            .padding(12.dp),
    ) {
        when (ui) {
            is WatchNextWidgetUi.Rows -> ui.rows.forEach { row ->
                WidgetRowView(row)
                Spacer(GlanceModifier.height(8.dp))
            }

            WatchNextWidgetUi.Empty.NOTHING_IN_PROGRESS -> EmptyView(
                title = LocalContext.current.getString(R.string.widget_nothing_title),
                body = LocalContext.current.getString(R.string.widget_nothing_body),
            )

            WatchNextWidgetUi.Empty.NO_CATALOG_YET -> EmptyView(
                title = LocalContext.current.getString(R.string.widget_no_catalog_title),
                body = LocalContext.current.getString(R.string.widget_no_catalog_body),
            )
        }
    }
}

@Composable
private fun EmptyView(title: String, body: String) {
    Column(
        modifier = GlanceModifier.fillMaxSize().clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = TextStyle(color = GlanceTheme.colors.onBackground, fontWeight = FontWeight.Medium))
        Text(body, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp), maxLines = 2)
    }
}

@Composable
private fun WidgetRowView(row: WidgetRow) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(GlanceTheme.colors.surfaceVariant)
            .cornerRadius(12.dp)
            .padding(10.dp)
            .clickable(
                actionStartActivity<MainActivity>(
                    // Reuses the extra EPIC 5's notifications already send, so
                    // tapping a widget row lands on the same screen a "new
                    // episode" notification does.
                    parameters = actionParametersOf(DeepLinkMediaIdKey to row.mediaId),
                ),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(GlanceModifier.defaultWeight()) {
            Text(
                row.title,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            Text(
                row.subtitle(LocalContext.current),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.width(8.dp))
        RowAction(row)
    }
}

@Composable
private fun RowAction(row: WidgetRow) {
    val undoable = row.undoable
    val tickable = row.tickable
    when {
        undoable != null -> WidgetButton(
            label = LocalContext.current.getString(R.string.widget_undo),
            onClick = actionRunCallback<UndoTickAction>(actionParametersOf(EpisodeIdKey to undoable.toString())),
        )

        tickable != null -> WidgetButton(
            label = LocalContext.current.getString(R.string.widget_seen),
            onClick = actionRunCallback<TickAction>(actionParametersOf(EpisodeIdKey to tickable.toString())),
        )

        else -> Unit
    }
}

@Composable
private fun WidgetButton(label: String, onClick: Action) {
    Text(
        label,
        style = TextStyle(color = GlanceTheme.colors.onPrimary, fontWeight = FontWeight.Medium, fontSize = 12.sp),
        modifier = GlanceModifier
            .background(GlanceTheme.colors.primary)
            .cornerRadius(12.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick),
    )
}

private fun WidgetRow.subtitle(context: Context): String = when {
    undoable != null -> context.getString(R.string.widget_marked_seen)
    episodeLabel == null -> context.getString(R.string.widget_open_to_load)
    episodeName != null -> "$episodeLabel · $episodeName"
    else -> episodeLabel
}

/** Parsed back in the action callbacks; parameters can only carry primitives. */
internal fun parseEpisodeId(raw: String?): EpisodeId? = raw?.let { runCatching { EpisodeId.parse(it) }.getOrNull() }
