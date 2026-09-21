package com.codingpit.muviss

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
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

        // Created out here rather than being left to MuvissApp's default,
        // because the MenuBar below is a composable of the Window's own scope
        // and has to share it. See MuvissAppController.
        val appController = rememberMuvissAppController()

        val quit = {
            persistWindowState(windowState)
            // Frees the loopback port if the user quits mid-sign-in.
            loopback.release()
            exitApplication()
        }

        Window(
            onCloseRequest = quit,
            state = windowState,
            title = "Muviss",
        ) {
            MuvissMenuBar(controller = appController, onQuit = quit)

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
                controller = appController,
            )
        }
    }
}

/**
 * The menu bar, which desktop had none of (issue #41): before this, macOS gave
 * the app only the default application menu and there was no keyboard route to
 * anything.
 *
 * A `FrameWindowScope` composable, so it can only be written here — which is
 * the whole reason [MuvissAppController] exists. Navigation goes through it
 * rather than through a `NavHostController` this module can see: androidx.
 * navigation is an `implementation` dependency of `:app:shared` and has no
 * business on this classpath to render a menu.
 *
 * `RadioButtonItem` rather than `Item` so View shows which destination is
 * open, the way a tab-shaped menu should. It reads
 * [MuvissAppController.currentDestination], which is snapshot state written by
 * the app's own composition — a `Flow` would not recompose this menu, since it
 * is composed in the Window's composition and not in the app's.
 *
 * Shortcuts are meta on macOS and ctrl everywhere else. Only two are bound:
 * Search and Settings are the two the issue named, and they are the two that
 * are genuinely reached often enough to be worth a chord. Quit gets one
 * because a desktop app without it feels broken.
 */
@Composable
private fun FrameWindowScope.MuvissMenuBar(
    controller: MuvissAppController,
    onQuit: () -> Unit,
) {
    MenuBar {
        Menu("File", mnemonic = 'F') {
            Item("Quit Muviss", shortcut = quitShortcut, onClick = onQuit)
        }
        Menu("View", mnemonic = 'V') {
            controller.destinations.forEach { destination ->
                RadioButtonItem(
                    text = destination.label,
                    selected = controller.currentDestination === destination,
                    shortcut = viewShortcuts[destination.label],
                    onClick = { controller.navigateTo(destination) },
                )
            }
        }
    }
}

/**
 * The platform's own modifier: Cmd on macOS, Ctrl elsewhere. `KeyShortcut`
 * takes them as separate flags and has no "primary modifier" of its own, so
 * every shortcut in this file has to make the choice explicitly — getting it
 * wrong is a menu whose accelerators simply never fire.
 *
 * Declared **above** the two properties that read it, because Kotlin
 * initialises top-level properties in declaration order. The compiler enforces
 * this ("Variable 'isMacOs' must be initialized"), so the ordering cannot rot
 * silently; it is called out only so nobody moves it and then wonders why.
 */
internal val isMacOs: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac")

internal fun accelerator(key: Key): KeyShortcut = KeyShortcut(key, meta = isMacOs, ctrl = !isMacOs)

/**
 * Quit's accelerator — and on macOS, deliberately none.
 *
 * macOS gives every app an application menu that already carries
 * "Quit <app>" ⌘Q, and AWT silently drops a duplicate: with
 * `KeyShortcut(Key.Q, meta = true)` set here, the item rendered with **no
 * accelerator at all** (confirmed by reading `AXMenuItemCmdChar` off the live
 * menu). Binding it is not wrong so much as a lie in the UI — it claims a
 * chord the item does not own. The system menu's ⌘Q still quits.
 *
 * Windows and Linux have no such menu, so there Ctrl+Q is the only one.
 */
internal val quitShortcut: KeyShortcut? = if (isMacOs) null else accelerator(Key.Q)

/**
 * The two destinations worth a chord, keyed by label rather than by index so
 * reordering the bar cannot silently move a shortcut onto a different screen.
 */
internal val viewShortcuts: Map<String, KeyShortcut> = mapOf(
    "Search" to accelerator(Key.F),
    "Settings" to accelerator(Key.Comma),
)

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
