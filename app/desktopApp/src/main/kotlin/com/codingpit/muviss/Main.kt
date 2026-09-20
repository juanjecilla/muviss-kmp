package com.codingpit.muviss

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import com.codingpit.muviss.core.database.DatabaseDriverFactory
import com.codingpit.muviss.core.sync.OAuthRedirectTarget
import com.codingpit.muviss.di.appModules
import com.codingpit.muviss.notifications.DesktopEpisodeRefresh
import com.codingpit.muviss.notifications.newEpisodesNotification
import com.codingpit.muviss.oauth.LoopbackRedirectServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.skia.Image
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import java.awt.Dimension
import java.awt.SystemTray

/**
 * Lives for the whole process, not for a composition: the loopback sign-in
 * server's timeout has to keep running while the user is away in a browser,
 * and a `rememberCoroutineScope` would be cancelled by any recomposition that
 * moved it.
 */
private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * Quit as soon as the app has drawn once. Set by CI's headless smoke step
 * (`MUVISS_EXIT_AFTER_FIRST_FRAME=1` under Xvfb), which asks the one question no
 * compile or unit test can: does this thing actually start?
 *
 * An environment variable rather than a system property, and not by preference:
 * Gradle does not forward `-D` to the JVM a JavaExec forks, so `./gradlew run
 * -Dmuviss...` sets it on the daemon and the app never sees it. The environment
 * *is* inherited. `MUVISS_RECORD_GOLDENS` in :core:testing reads the same way.
 *
 * That question earned its own step. EPIC 22 shipped a full `./gradlew build`
 * across six targets with two thousand passing tests, and the app did not
 * launch — a Koin construction cycle is only visible once something is really
 * built and run. A frame on screen means the graph resolved, the driver opened
 * the database, and the theme composed.
 */
private val exitAfterFirstFrame: Boolean = System.getenv("MUVISS_EXIT_AFTER_FIRST_FRAME") == "1"

fun main() {
    // Koin starts here rather than relying only on `MuvissApp()`'s
    // `KoinApplication` composable, for the same reason Android's
    // `MuvissApplication.onCreate` and iOS's `IosAppStartup.start` do it:
    // something outside the composition needs the graph. Here it is the
    // loopback OAuth server, which must be bound *into* the graph so
    // CoreSyncRepository resolves it instead of the scheme-based default.
    // Guarded by `getOrNull()` in case something started it first; `MuvissApp()`
    // reuses a running global instance rather than starting a second one (see
    // its doc comment), which is also why DatabaseDriverFactory has to be bound
    // here — the composable's own platformDatabaseModule never applies once a
    // global instance exists.
    // Crash reporting first, before Koin and long before Compose (EPIC 26): a
    // failure while the graph is built is exactly what it is for. It reads the
    // stored opt-out through a short-lived driver of its own.
    MuvissCrashReporting.start(DatabaseDriverFactory())
    if (GlobalContext.getOrNull() == null) {
        startKoin {
            modules(
                appModules + module {
                    single { DatabaseDriverFactory() }
                    single { LoopbackRedirectServer(appScope, get()) }
                    single { DesktopEpisodeRefresh(get(), get(), get()) }
                    // Overrides syncModule's DeepLinkRedirectTarget. Koin's last
                    // binding wins — the same mechanism BillingSyncBridge uses
                    // for EntitlementGate, and the same ordering requirement.
                    single<OAuthRedirectTarget> { get<LoopbackRedirectServer>() }
                },
            )
        }
    }
    MuvissCrashReporting.followSettings()
    val koin = GlobalContext.get()
    val loopback = koin.get<LoopbackRedirectServer>()
    val episodeRefresh = koin.get<DesktopEpisodeRefresh>()

    application {
        // See DesktopWindowState.kt for why this is java.util.prefs rather than
        // the shared appSettings table.
        val windowState = remember { restoredWindowState() }

        // The authorization code takes the same route Android's MainActivity
        // sends it: into MuvissApp's `oauthCode` parameter, so
        // CompleteOAuthOnRedirect stays the only caller of completeOAuth
        // anywhere (ADR 0014). Desktop's code arrives inline from the loopback
        // server rather than as an Intent, but nothing downstream can tell.
        var oauthCode by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(loopback) {
            loopback.codes.collect { oauthCode = it }
        }

        // Belt-and-suspenders: onCloseRequest below covers the normal quit path,
        // but this also captures drags/resizes if the process ever dies without
        // a clean close (force-quit, `kill`). `collectLatest` + a trailing delay
        // is a manual debounce (kotlinx.coroutines' own `Flow.debounce` is a
        // @FlowPreview API): each new emission cancels the previous 500ms wait,
        // so a drag/resize in progress doesn't hammer java.util.prefs every frame.
        LaunchedEffect(windowState) {
            snapshotFlow { windowState.size to windowState.position }
                .collectLatest {
                    delay(WINDOW_PERSIST_DEBOUNCE_MS)
                    persistWindowState(windowState)
                }
        }

        // AWT's SystemTray is absent on some Linux desktop environments, and
        // Compose's TrayState delivers nothing without a Tray composable to
        // deliver through. Where it is missing there is no icon and no popup —
        // but the refresh below still runs, because a current episode catalog
        // is worth having whether or not anyone can be told about it.
        val trayAvailable = remember { SystemTray.isSupported() }
        val trayState = rememberTrayState()
        val trayIcon = remember { loadTrayIcon() }
        if (trayAvailable) {
            Tray(state = trayState, icon = trayIcon, tooltip = "Muviss")
        }

        LaunchedEffect(episodeRefresh, trayAvailable) {
            while (true) {
                val results = episodeRefresh.runOnce()
                if (trayAvailable) {
                    newEpisodesNotification(results)?.let { (title, message) ->
                        trayState.sendNotification(Notification(title, message, Notification.Type.Info))
                    }
                }
                delay(DesktopEpisodeRefresh.INTERVAL)
            }
        }

        Window(
            onCloseRequest = {
                persistWindowState(windowState)
                // Frees the loopback port if the user quits mid-sign-in.
                loopback.release()
                exitApplication()
            },
            state = windowState,
            title = "Muviss",
        ) {
            // WindowScope.window is the underlying java.awt/Swing peer
            // (ComposeWindow extends JFrame) — minimumSize has no Compose-level
            // equivalent on the WindowState/Window API, so it's set here once.
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(MIN_WINDOW_SIZE.width.value.toInt(), MIN_WINDOW_SIZE.height.value.toInt())
                if (exitAfterFirstFrame) {
                    // Inside the Window's content, so reaching here means the
                    // composition ran rather than merely that main() did.
                    println("muviss: first frame composed")
                    exitApplication()
                }
            }
            MuvissApp(
                oauthCode = oauthCode,
                onOAuthCodeConsumed = { oauthCode = null },
            )
        }
    }
}

/**
 * The tray icon: the same artwork the installers use, loaded off the classpath
 * (`icons/` is a resource root — see build.gradle.kts) so there is one copy of
 * it rather than a second under src/main/resources.
 *
 * Decoded through Skia rather than `androidx.compose.ui.res.painterResource`,
 * whose String overload is deprecated in favour of the Compose resources
 * library — which this module cannot use without a `composeResources` layout it
 * has no other reason to adopt.
 */
private fun loadTrayIcon(): Painter {
    val bytes = checkNotNull(Main::class.java.getResourceAsStream("/icon.png")) {
        "icon.png is missing from the classpath; is `icons/` still a resource root?"
    }.use { it.readBytes() }
    return BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
}

/** Anchors [loadTrayIcon]'s classpath lookup; `Main.kt` compiles to `MainKt`, which has no Kotlin class literal. */
private class Main
