package com.codingpit.muviss.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
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
import com.codingpit.muviss.core.designsystem.theme.muvissColorScheme
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.models.EpisodeId
import kotlinx.coroutines.flow.first
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

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val items = progressApi.observeWatchNext().first()
        val justTicked = readJustTicked(context, id)

        provideContent {
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
                title = "Nothing in progress",
                body = "Start watching something and it will show up here.",
            )

            WatchNextWidgetUi.Empty.NO_CATALOG_YET -> EmptyView(
                title = "Open Muviss once",
                body = "Episode details are downloaded the first time you open the app.",
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
                row.subtitle(),
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
            label = "Undo",
            onClick = actionRunCallback<UndoTickAction>(actionParametersOf(EpisodeIdKey to undoable.toString())),
        )

        tickable != null -> WidgetButton(
            label = "Seen",
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

private fun WidgetRow.subtitle(): String = when {
    undoable != null -> "Marked seen"
    episodeLabel == null -> "Open to load episodes"
    episodeName != null -> "$episodeLabel · $episodeName"
    else -> episodeLabel
}

/** Parsed back in the action callbacks; parameters can only carry primitives. */
internal fun parseEpisodeId(raw: String?): EpisodeId? = raw?.let { runCatching { EpisodeId.parse(it) }.getOrNull() }
