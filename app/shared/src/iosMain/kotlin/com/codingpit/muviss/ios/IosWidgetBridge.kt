package com.codingpit.muviss.ios

import com.codingpit.muviss.core.database.DatabaseDriverFactory
import com.codingpit.muviss.di.appModules
import com.codingpit.muviss.feature.progress.api.ProgressApi
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
)

/**
 * What the iOS widget extension calls (EPIC 22).
 *
 * The extension is a **separate process**. It gets no `AppDelegate`, so
 * [IosAppStartup.start] never runs there — [ensureStarted] is this side's
 * equivalent, starting a Koin graph inside the extension over the same App
 * Group database the app writes to (ADR 0014). Without the App Group the
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
     * Starts Koin if this process has none — the normal case in a widget
     * extension. Idempotent, and safe to call from the app too.
     */
    fun ensureStarted() {
        if (KoinPlatformTools.defaultContext().getOrNull() == null) {
            startKoin {
                modules(appModules + module { single { DatabaseDriverFactory() } })
            }
        }
    }

    /** The watch-next rows, newest state, at most [limit] of them. */
    fun watchNextRows(limit: Int): List<IosWidgetRow> {
        ensureStarted()
        return runBlocking {
            progressApi.observeWatchNext().first().take(limit).map { item ->
                IosWidgetRow(
                    mediaId = item.mediaId.toString(),
                    title = item.title,
                    episodeLabel = item.nextEpisode?.let { "S${it.seasonNumber} · E${it.episodeNumber}" },
                    episodeName = item.nextEpisode?.name,
                    episodeId = item.nextEpisode?.id?.toString(),
                    seenCount = item.seenCount,
                    airedCount = item.airedCount,
                )
            }
        }
    }

    /**
     * Ticks an episode from the widget. Same call the app makes, so the tick
     * and its `episodePlay` row land in one transaction (ADR 0011).
     *
     * Two processes therefore write this database. SQLite's WAL journal is
     * what makes that safe, and `NativeSqliteDriver` enables it by default;
     * the App Group container is the only place both processes can reach the
     * same file to do it (ADR 0014).
     */
    fun tick(episodeId: String) = setSeen(episodeId, seen = true)

    /** Takes a widget tick back, clearing the viewing it recorded — the app's own undo. */
    fun undoTick(episodeId: String) = setSeen(episodeId, seen = false)

    private fun setSeen(episodeId: String, seen: Boolean) {
        ensureStarted()
        val parsed = runCatching { EpisodeId.parse(episodeId) }.getOrNull() ?: return
        runBlocking { progressApi.setEpisodeSeen(parsed, seen) }
    }
}
