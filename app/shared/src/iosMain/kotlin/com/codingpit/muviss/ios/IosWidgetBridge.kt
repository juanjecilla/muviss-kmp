package com.codingpit.muviss.ios

import com.codingpit.muviss.MuvissCrashReporting
import com.codingpit.muviss.MuvissDeepLinks
import com.codingpit.muviss.core.common.notifications.NotificationPermissionRequester
import com.codingpit.muviss.core.database.DatabaseDriverFactory
import com.codingpit.muviss.di.appModules
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.progress.api.WatchNextItem
import com.codingpit.muviss.models.EpisodeId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools

/**
 * One flat row for the SwiftUI widget, so the extension never has to see a
 * Kotlin `Flow`, a `MediaId` or an `EpisodeId`.
 *
 * Ordinary `String`s rather than the real types on purpose: the `Shared`
 * framework exports only `:app:shared`'s own public API (there is no
 * `export(...)` in its Gradle config), and widening that export so a widget
 * could name three model types would put the whole progress contract into
 * Swift's headers. [episodeId] is round-tripped through
 * [EpisodeId.toString]/[EpisodeId.parse], which is already the wire form the
 * database stores.
 */
data class IosWidgetRow(
    val mediaId: String,
    val title: String,
    val episodeLabel: String?,
    val episodeName: String?,
    val episodeId: String?,
    val seenCount: Int,
    val airedCount: Int,
    /** False while the title's catalog has not arrived: the widget then says "open the app" rather than "nothing". */
    val catalogLoaded: Boolean,
)

/**
 * What the iOS widget extension calls (EPIC 22).
 *
 * The extension is a **separate process**. It gets no `AppDelegate`, so
 * [IosAppStartup.start] never runs there — [ensureStarted] is this side's
 * equivalent, starting a Koin graph inside the extension over the same App
 * Group database the app writes to (ADR 0016). Without the App Group the
 * extension would open its own empty file and the widget would show an empty
 * library forever.
 *
 * Every entry point is blocking rather than suspending. Kotlin/Native exports
 * a `suspend fun` to Swift as a completion-handler callback, and WidgetKit's
 * `TimelineProvider` and `AppIntent.perform()` are both already async
 * contexts with their own budget — a plain synchronous call the Swift side
 * awaits on its own executor is far easier to get right than nesting two
 * async models, and the reads involved are local SQLite.
 */
object IosWidgetBridge : KoinComponent {

    private val progressApi: ProgressApi by inject()

    /**
     * The link the widget's empty state opens (`muviss://next-pick`, EPIC 51). Owned here, next to the
     * parser in `IosDeepLinks`, so the SwiftUI side names no scheme or host of its own.
     */
    val nextPickUrl: String = MuvissDeepLinks.NEXT_PICK_URL

    private val undoMarker: WidgetUndoMarker by lazy { WidgetUndoMarker.appGroup() }

    /**
     * Starts Koin if this process has none — the normal case in a widget
     * extension. Idempotent, and safe to call from the app too.
     *
     * Crash reporting starts here as well, as the first thing in the block, the
     * way [IosAppStartup.start] starts it in the app: a widget that crashes while
     * the graph is built is exactly the failure the home screen would otherwise
     * hide. The app has already started it by the time this runs there, so the
     * block is skipped and nothing is started twice.
     *
     * [IosNotificationSettings] is deliberately not bound in this graph. The
     * widget resolves only [ProgressApi], which never reads it, and its `open()`
     * needs `UIApplication`, which an extension does not have. The shared
     * default (`SystemNotificationSettings.None`) therefore applies.
     */
    fun ensureStarted() {
        if (KoinPlatformTools.defaultContext().getOrNull() == null) {
            MuvissCrashReporting.start(DatabaseDriverFactory())
            startKoin {
                modules(
                    appModules + module {
                        single { DatabaseDriverFactory() }
                        single<NotificationPermissionRequester> { IosNotificationPermissionRequester }
                    },
                )
            }
            MuvissCrashReporting.followSettings()
        }
    }

    /** The Undo marker's episode id, if the widget has just ticked one. */
    fun justTicked(): String? = undoMarker.justTicked()

    /**
     * "S2 · E5" for the episode the widget just ticked, so its confirmation can
     * name what was marked. Null when [episodeId] does not parse. The format
     * matches [watchNextRows] and the Android widget's row label.
     */
    fun tickedLabel(episodeId: String): String? = runCatching { EpisodeId.parse(episodeId) }.getOrNull()
        ?.let { "S${it.seasonNumber} · E${it.episodeNumber}" }

    /** Drops the Undo marker. Called by the Undo intent, and by the app's refresh on every write. */
    fun clearJustTicked() = undoMarker.clear()

    /** One colour of the widget's palette as ARGB, read from `MuvissPalette` (see [IosWidgetPalette]). */
    fun colorArgb(role: String, dark: Boolean): Int = IosWidgetPalette.argb(role, dark)

    /**
     * The watch-next rows, newest state, at most [limit] of them. The just-ticked title's row is kept at the top
     * while the marker holds it (#888), even when the tick has taken the title out of the shared list.
     */
    fun watchNextRows(limit: Int): List<IosWidgetRow> {
        ensureStarted()
        return runBlocking {
            val items = progressApi.observeWatchNext().first()
            watchNextRowsFor(items, justTicked(), undoMarker.justTickedTitle(), limit)
        }
    }

    /**
     * [watchNextRows] over the given items and marker values. Split out so the rows can be tested without
     * starting the graph: `ensureStarted` boots the whole app module set over the real database.
     */
    internal fun watchNextRowsFor(items: List<WatchNextItem>, ticked: String?, title: String?, limit: Int): List<IosWidgetRow> {
        val rows = items.map { item ->
            IosWidgetRow(
                mediaId = item.mediaId.toString(),
                title = item.title,
                episodeLabel = item.nextEpisode?.let { "S${it.seasonNumber} · E${it.episodeNumber}" },
                episodeName = item.nextEpisode?.name,
                episodeId = item.nextEpisode?.id?.toString(),
                seenCount = item.seenCount,
                airedCount = item.airedCount,
                catalogLoaded = item.catalogLoaded,
            )
        }
        return (listOfNotNull(retainedTickRow(items, ticked, title)) + rows).take(limit)
    }

    /**
     * The just-ticked title's row, kept by the widget (#888). Null while the shared list still has the title, since
     * its own row carries Undo, or when the marker has no title or no parsable episode.
     */
    private fun retainedTickRow(items: List<WatchNextItem>, ticked: String?, title: String?): IosWidgetRow? {
        val episode = ticked?.let { runCatching { EpisodeId.parse(it) }.getOrNull() } ?: return null
        if (title == null || items.any { it.mediaId == episode.show }) return null
        return IosWidgetRow(
            mediaId = episode.show.toString(),
            title = title,
            episodeLabel = null,
            episodeName = null,
            episodeId = null,
            seenCount = 0,
            airedCount = 0,
            catalogLoaded = true,
        )
    }

    /**
     * Ticks an episode from the widget. Same call the app makes, so the tick
     * and its `episodePlay` row land in one transaction (ADR 0011).
     *
     * Two processes therefore write this database. SQLite's WAL journal is
     * what makes that safe, and `NativeSqliteDriver` enables it by default;
     * the App Group container is the only place both processes can reach the
     * same file to do it (ADR 0016).
     */
    fun tick(episodeId: String) {
        ensureStarted()
        val parsed = runCatching { EpisodeId.parse(episodeId) }.getOrNull() ?: return
        runBlocking {
            // Read before the write: the tick can take the title out of Watch Next. The marker is set after the
            // write, so the reload that follows finds it (#888). The previous marker is read before the write too.
            val title = progressApi.observeWatchNext().first().firstOrNull { it.mediaId == parsed.show }?.title
            val markedEpisode = undoMarker.justTicked()
            val markedTitle = undoMarker.justTickedTitle()
            progressApi.setEpisodeSeen(parsed, seen = true)
            undoMarker.markJustTicked(episodeId, rememberedTickTitle(episodeId, title, markedEpisode, markedTitle))
        }
    }

    /** Takes a widget tick back, clearing the viewing it recorded — the app's own undo. */
    fun undoTick(episodeId: String) = setSeen(episodeId, seen = false)

    private fun setSeen(episodeId: String, seen: Boolean) {
        ensureStarted()
        val parsed = runCatching { EpisodeId.parse(episodeId) }.getOrNull() ?: return
        runBlocking { progressApi.setEpisodeSeen(parsed, seen) }
    }
}

/**
 * The title the Undo row keeps for a tick (#888). A repeated tick of the episode the marker already names finds no
 * Watch Next item, and keeps the remembered title rather than dropping it. A tick of a different episode keeps only
 * the title it found. Android's `rememberedTickTitle` in `WidgetActions.kt` applies the same rule.
 */
internal fun rememberedTickTitle(
    episodeId: String,
    title: String?,
    markedEpisodeId: String?,
    markedTitle: String?,
): String? = title ?: markedTitle.takeIf { markedEpisodeId == episodeId }
