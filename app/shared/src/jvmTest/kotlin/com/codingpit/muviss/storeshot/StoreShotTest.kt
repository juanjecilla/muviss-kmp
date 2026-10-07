@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.storeshot

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.codingpit.muviss.MuvissApp
import com.codingpit.muviss.core.database.DatabaseDriverFactory
import com.codingpit.muviss.core.network.MetadataProvider
import com.codingpit.muviss.core.sync.SyncAvailability
import com.codingpit.muviss.di.appModules
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.profile.domain.ProfileRepository
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.settings.domain.AppTheme
import com.codingpit.muviss.feature.settings.domain.SettingsRepository
import com.codingpit.muviss.models.EpisodeId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

/**
 * StoreShot (EPIC 33, #76, #168): the Play screenshots and the landing's
 * screenshots, rendered from the real app rather than captured by hand.
 *
 * The real Koin graph runs over a temp database seeded through the real APIs,
 * with [StoreShotCatalog] as the only metadata source, and the real
 * `MuvissApp()` is driven through its bottom bar at a phone's size and
 * density (1080x1920 px at 2.625x, i.e. 411dp wide — the phone layout).
 * Posters come from [StoreShotPosters] through Coil's preview handler, which
 * is why the content runs with `LocalInspectionMode` on.
 *
 * Off by default; it writes into the repository:
 *
 *   ./gradlew :app:shared:jvmTest --tests '*StoreShotTest*' -Pstoreshot
 *
 * - `fastlane/metadata/android/<locale>/images/phoneScreenshots/` — Play, en-US and es-ES, light
 * - `website/public/screenshots/<name>-{light,dark}.png` — the landing, English
 *
 * 9:16, because Play refuses a screenshot whose long side is over twice the short one.
 */
class StoreShotTest {

    private val enabled = System.getProperty("muviss.storeshot") == "true"

    @Test
    fun render_the_store_and_landing_screenshots() {
        if (!enabled) return
        val today = System.currentTimeMillis() / DAY_MS
        val catalog = StoreShotCatalog(today)
        val koin = startGraph(catalog)
        val defaultLocale = Locale.getDefault()
        // viewModelScope runs on Main, which a test JVM has no platform
        // dispatcher for; the other UI suites install one the same way.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            runBlocking { seed(koin, catalog, today) }
            // en-US only until EPIC 31 (#74) translates the feature screens: a Spanish
            // shot would show mostly English copy. Play falls back to the default
            // language's screenshots for the es-ES listing.
            for ((locale, dark) in listOf("en-US" to false, "en-US" to true)) {
                runBlocking { koin.get<SettingsRepository>().setTheme(if (dark) AppTheme.DARK else AppTheme.LIGHT) }
                Locale.setDefault(Locale.forLanguageTag(locale))
                shoot(catalog, locale, dark)
            }
        } finally {
            Locale.setDefault(defaultLocale)
            stopKoin()
            Dispatchers.resetMain()
        }
    }

    private fun startGraph(catalog: StoreShotCatalog): Koin {
        val directory = createTempDirectory("muviss-storeshot").toFile()
        return startKoin {
            modules(
                appModules + module {
                    single { DatabaseDriverFactory(directory) }
                    // Overrides networkModule's TmdbProvider: nothing reaches TMDB.
                    single<MetadataProvider> { catalog.provider() }
                    // The release build's gate: sync and co-watch are paid and
                    // compiled out of v1 (ADR 0018, ADR 0024), so the store must
                    // never show their UI — even when this machine's
                    // local.properties turns sync on for development.
                    single<SyncAvailability> { SyncAvailability { false } }
                },
            )
        }.koin
    }

    private suspend fun seed(koin: Koin, c: StoreShotCatalog, today: Long) {
        val collection = koin.get<CollectionApi>()
        val progress = koin.get<ProgressApi>()
        for (title in c.library) collection.add(c.details(title.id))

        fun seasons(title: StoreShotCatalog.Title) = c.details(title.id).seasons

        // Watching: complete seasons, then part of the current one.
        suspend fun watchUpTo(title: StoreShotCatalog.Title, fullSeasons: Int, episodesIntoNext: Int) {
            val all = seasons(title)
            all.take(fullSeasons).forEach { progress.markSeasonAiredSeen(it, today) }
            all.getOrNull(fullSeasons)?.episodes?.take(episodesIntoNext)?.forEach { progress.setEpisodeSeen(it.id, true) }
        }
        watchUpTo(c.lighthouse, fullSeasons = 1, episodesIntoNext = 4)
        watchUpTo(c.northbound, fullSeasons = 2, episodesIntoNext = 2)
        watchUpTo(c.orchard, fullSeasons = 0, episodesIntoNext = 5)
        progress.markShowAiredSeen(seasons(c.paperMoons), today)
        progress.markShowAiredSeen(seasons(c.glassHarbor), today)
        listOf(c.copperSky, c.smallHours, c.winterSignal).forEach { progress.setMovieWatched(it.id, true) }

        // A few rewatches, so "Most rewatched" and the plays have something to show.
        seasons(c.glassHarbor).first().episodes.take(3).forEach { progress.recordPlay(it.id) }
        progress.recordPlay(EpisodeId(c.paperMoons.id, 1, 1))

        koin.get<ProfileRepository>().setDisplayName("Alex")
        collection.setFavorite(c.lighthouse.id, true)
        collection.setFavorite(c.glassHarbor.id, true)
        mapOf(c.glassHarbor to 9, c.paperMoons to 9, c.copperSky to 8, c.smallHours to 7, c.lighthouse to 8)
            .forEach { (title, rating) -> collection.setRating(title.id, rating) }
    }

    private fun shoot(catalog: StoreShotCatalog, locale: String, dark: Boolean) = runSkikoComposeUiTest(
        size = Size(WIDTH_PX.toFloat(), HEIGHT_PX.toFloat()),
        density = Density(DENSITY),
    ) {
        val posters = AsyncImagePreviewHandler { request ->
            val name = request.data.toString().substringAfterLast(StoreShotCatalog.POSTER_PREFIX, "")
            val titles = catalog.library + catalog.fresh
            val index = titles.indexOfFirst { name.isNotEmpty() && it.summary.posterUrl?.contains(name) == true }
            StoreShotPosters.render(titles.getOrNull(index)?.summary?.title ?: "Muviss", index.coerceAtLeast(0)).asImage()
        }
        setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides posters) {
                MuvissApp()
            }
        }
        val nav = NAV.getValue(locale.substringBefore('-'))
        val shots = mutableListOf<Pair<String, java.awt.image.BufferedImage>>()
        fun capture(name: String) {
            waitForIdle()
            shots += name to onRoot().captureToImage().toAwtImage()
        }

        settle { onAllNodes(hasText("Fill your library")).fetchSemanticsNodes().isNotEmpty() }
        capture("discover")

        tab(nav.library)
        settle { onAllNodes(hasText("Watching", substring = true) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasText("Watching", substring = true) and hasClickAction())[0].performClick()
        settle { onAllNodes(hasContentDescription(catalog.lighthouse.summary.title)).fetchSemanticsNodes().isNotEmpty() }
        capture("library")

        // The card merges its poster and title into one clickable node.
        val lighthouse = catalog.lighthouse.summary.title
        onAllNodes(hasClickAction() and (hasContentDescription(lighthouse) or hasText(lighthouse) or hasAnyDescendantDescribed(lighthouse)))[0].performClick()
        settle { onAllNodes(hasText("Season 2")).fetchSemanticsNodes().isNotEmpty() }
        // Detail opens the season the user is in the middle of; expand only if it did not.
        val nextEpisode = catalog.details(catalog.lighthouse.id).seasons[1].episodes[5].name
        if (onAllNodes(hasText(nextEpisode, substring = true)).fetchSemanticsNodes().isEmpty()) {
            onAllNodes(hasTestTag("seasonHeader2"))[0].performScrollTo().performClick()
        }
        settle { onAllNodes(hasText(nextEpisode, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasText(nextEpisode, substring = true))[0].performScrollTo()
        // "Seasons (2)" just under the floating back button, ticks below it.
        bringToTop("Seasons (2)", marginPx = 150f)
        capture("detail-seasons")

        tab(nav.progress)
        settle { onAllNodes(hasText(catalog.lighthouse.summary.title)).fetchSemanticsNodes().isNotEmpty() }
        capture("watch-next")

        tab(nav.profile)
        settle { onAllNodes(hasText("Stats")).fetchSemanticsNodes().isNotEmpty() }
        // The identity block is the top half; the store shot is the numbers and charts.
        onAllNodes(hasText("Stats"))[0].performScrollTo()
        bringToTop("Stats", marginPx = 60f)
        capture("profile-stats")

        tab(nav.search)
        settle { onAllNodes(hasText("Fill your library")).fetchSemanticsNodes().isNotEmpty() }
        onAllNodes(hasText("Fill your library"))[0].performClick()
        onAllNodes(hasText("Got it")).fetchSemanticsNodes().firstOrNull()?.let { onAllNodes(hasText("Got it"))[0].performClick() }
        settle {
            onAllNodes(hasText("Got it")).fetchSemanticsNodes().firstOrNull()?.let { onAllNodes(hasText("Got it"))[0].performClick() }
            onAllNodes(hasTestTag("triage-card")).fetchSemanticsNodes().isNotEmpty()
        }
        capture("triage")

        write(shots, locale, dark)
    }

    private fun SkikoComposeUiTest.tab(label: String) {
        onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes().firstOrNull()
            ?.let { onAllNodes(hasText(label) and hasClickAction())[0].performClick() }
            ?: onAllNodes(hasClickAction() and hasAnyDescendantText(label))[0].performClick()
    }

    /**
     * Scrolls until the node with [text] sits [marginPx] below the top edge.
     * The drag is slow so it stops where it is released instead of flinging.
     */
    private fun SkikoComposeUiTest.bringToTop(text: String, marginPx: Float) {
        waitForIdle()
        val topPx = onAllNodes(hasText(text))[0].getUnclippedBoundsInRoot().top.value * DENSITY
        val distance = topPx - marginPx
        if (kotlin.math.abs(distance) < 4f) return
        onRoot().performTouchInput {
            val startY = if (distance > 0) bottom * 0.85f else bottom * 0.15f
            swipe(start = androidx.compose.ui.geometry.Offset(centerX, startY), end = androidx.compose.ui.geometry.Offset(centerX, startY - distance), durationMillis = 2_000)
        }
        waitForIdle()
    }

    private fun SkikoComposeUiTest.settle(condition: () -> Boolean) {
        try {
            waitUntil(timeoutMillis = 20_000) { condition() }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            // What the screen showed instead, to diagnose a step that never settled.
            val debug = File(System.getProperty("java.io.tmpdir"), "muviss-storeshot-timeout.png")
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", debug)
            throw AssertionError("StoreShot step did not settle; screen saved to $debug", timeout)
        }
        // Let images and the last recomposition land before capturing.
        mainClock.advanceTimeBy(500)
        waitForIdle()
    }

    private fun write(shots: List<Pair<String, java.awt.image.BufferedImage>>, locale: String, dark: Boolean) {
        val root = repoRoot()
        if (!dark) {
            val play = File(root, "fastlane/metadata/android/$locale/images/phoneScreenshots").apply {
                deleteRecursively()
                mkdirs()
            }
            PLAY_ORDER.forEachIndexed { i, name ->
                shots.firstOrNull { it.first == name }?.let { ImageIO.write(it.second, "png", File(play, "${i + 1}_$name.png")) }
            }
        }
        if (locale == "en-US") {
            val site = File(root, "website/public/screenshots").apply { mkdirs() }
            val theme = if (dark) "dark" else "light"
            for ((name, image) in shots) if (name in SITE_NAMES) ImageIO.write(image, "png", File(site, "$name-$theme.png"))
        }
    }

    private class Nav(val search: String, val library: String, val progress: String, val profile: String)

    private companion object {
        const val WIDTH_PX = 1080
        const val HEIGHT_PX = 1920
        const val DENSITY = 2.625f
        const val DAY_MS = 86_400_000L
        val NAV = mapOf(
            "en" to Nav("Search", "Library", "Progress", "Profile"),
            "es" to Nav("Buscar", "Biblioteca", "Progreso", "Perfil"),
        )
        val PLAY_ORDER = listOf("library", "detail-seasons", "watch-next", "triage", "profile-stats", "discover")
        val SITE_NAMES = setOf("detail-seasons", "library", "watch-next", "triage", "profile-stats")
    }
}

private fun hasAnyDescendantDescribed(description: String) = androidx.compose.ui.test.hasAnyDescendant(hasContentDescription(description))

private fun hasAnyDescendantText(text: String) = androidx.compose.ui.test.hasAnyDescendant(hasText(text))
