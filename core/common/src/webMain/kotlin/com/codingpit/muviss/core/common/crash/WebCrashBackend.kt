package com.codingpit.muviss.core.common.crash

import kotlin.concurrent.Volatile

/**
 * `platformCrashBackend()`'s `actual` for **both** web targets, declared once
 * here rather than once per target — the same trick `sentryMain` (see
 * `core/common/build.gradle.kts`) uses to host one `actual` for
 * android/jvm/ios (#109). The default hierarchy template puts `webMain` in
 * the `dependsOn` closure of js *and* wasmJs, so a single `actual` here
 * satisfies both, and [WebCrashBackend] — the composition, gating and
 * scrubbing — is exercised on both platforms rather than being a copy
 * compile-checked on only one.
 *
 * `sentry-kotlin-multiplatform` (the SDK [SentryBackend] uses for the other
 * three platforms) publishes stub, no-op implementations for js/wasmJs, which
 * is why `CrashReporter` used to be a deliberate no-op on web (#83). This
 * does not wait for that SDK to grow real web targets: it drives Sentry's
 * standalone browser bundle directly, loaded from Sentry's CDN — the install
 * method Sentry documents for a page that does not bundle the SDK via npm —
 * through the plain `window.Sentry` global. [SentryJs] is the thin per-target
 * seam onto that global. It cannot live here in `webMain` itself: `webMain`
 * only has a *metadata* compilation (`compileWebMainKotlinMetadata`), which
 * cannot contain a `js(...)` call or an `external` declaration — confirmed
 * the same way `DatabaseFactory.web.kt`'s two actuals were kept separate
 * ("it would be one `webMain` file if it could be"). [SentryJs]'s two actuals
 * are consequently byte-for-byte the same code, which that file's siblings
 * already accept for the same reason.
 */
internal actual fun platformCrashBackend(): CrashBackend = WebCrashBackend

/**
 * The CDN script is injected **only** from [start] — and [CrashReporterCore.init]
 * never calls [start] at all when `config.dsn` is blank, the default for
 * every local and CI build (see [CrashReportingConfig]). So an unconfigured
 * web build makes no request to Sentry's CDN and loads no third-party
 * script: the same "never touches Sentry" guarantee `docs/PRIVACY.md` makes
 * for the other three platforms holds here too, even though it is enforced
 * differently — Android/iOS/JVM link the SDK at compile time regardless of
 * the DSN and rely on `beforeSend`/a blank-DSN `Sentry.init` no-op instead.
 *
 * Delegates every real decision to [QueuedCaptureBackend] over the real
 * [SentryJs] — split out so that queuing/gating logic is a plain Kotlin class
 * a `webTest` can exercise with fakes ([QueuedCaptureBackendTest]) rather than
 * needing a browser, the same way `SchemaStepTest` covers
 * `SchemaEnsuringDriver`'s create-vs-migrate decision without one.
 */
internal object WebCrashBackend : CrashBackend by QueuedCaptureBackend(
    load = SentryJs::load,
    init = SentryJs::init,
    captureException = SentryJs::captureException,
)

/**
 * A crash reported before the script finishes loading — realistically only
 * the first instant of a cold start — is queued in [pending] and flushed once
 * [load]'s callback fires. Best-effort, the same trade this file's neighbours
 * already make for the web worker's snapshot debounce.
 *
 * [load]/[init]/[captureException] are plain function types rather than
 * [SentryJs] itself so a test can fake them without a browser; [WebCrashBackend]
 * is the only real caller and always wires up the genuine [SentryJs].
 */
internal class QueuedCaptureBackend(
    private val load: (url: String, onLoad: () -> Unit) -> Unit,
    @Suppress("LongParameterList")
    private val init: (
        dsn: String,
        environment: String,
        release: String,
        dist: String,
        isEnabled: () -> Boolean,
        scrub: (String) -> String,
    ) -> Unit,
    private val captureException: (type: String, message: String, stack: String) -> Unit,
) : CrashBackend {

    @Volatile
    private var ready = false
    private val pending = mutableListOf<Throwable>()

    override fun start(config: CrashReportingConfig, gate: CrashReportGate) {
        load(SENTRY_BROWSER_SDK_URL) {
            init(config.dsn, config.environment, config.release, config.dist, { gate.enabled }, CrashScrubber::scrub)
            ready = true
            val queued = pending.toList()
            pending.clear()
            queued.forEach(::send)
        }
    }

    override fun capture(throwable: Throwable) {
        if (ready) send(throwable) else pending += throwable
    }

    private fun send(throwable: Throwable) {
        captureException(
            throwable::class.simpleName ?: "Throwable",
            CrashScrubber.scrub(throwable.message ?: ""),
            CrashScrubber.scrub(throwable.stackTraceToString()),
        )
    }
}

/**
 * Sentry's CDN distribution of `@sentry/browser`, pinned to an exact version —
 * checked live against the published package on 2026-09-29 (`npm view
 * @sentry/browser version`; the URL below 200s). Unlike the "Loader Script"
 * Sentry's own dashboard generates per-project (which embeds one specific
 * project's DSN baked in at generation time, so it cannot carry the
 * build-time-configured DSN [CrashReportingConfig] supplies), this is the
 * plain SDK bundle: it defines `window.Sentry` and does nothing else until
 * [SentryJs.init] is called.
 */
internal const val SENTRY_BROWSER_SDK_VERSION = "11.1.0"
internal const val SENTRY_BROWSER_SDK_URL = "https://browser.sentry-cdn.com/$SENTRY_BROWSER_SDK_VERSION/bundle.min.js"

/**
 * The per-target seam onto the `window.Sentry` global — js/wasmJs actuals in
 * `SentryJs.js.kt` / `SentryJs.wasmJs.kt`. [load] injects the `<script>` tag
 * and invokes [onLoad] once it fires; [init] and [captureException] assume
 * the script has already loaded (callers only reach them from inside
 * [load]'s callback or after it has fired — see [WebCrashBackend]).
 */
internal expect object SentryJs {
    fun load(url: String, onLoad: () -> Unit)

    // The four strings mirror CrashReportingConfig's fields, kept separate
    // rather than taking that type directly: the js(...)-bodied actual has to
    // reference each by name inside a raw JS string, and a plain Kotlin
    // class's properties are not something a js(...) snippet can be trusted
    // to read back out by name the same way.
    @Suppress("LongParameterList")
    fun init(
        dsn: String,
        environment: String,
        release: String,
        dist: String,
        isEnabled: () -> Boolean,
        scrub: (String) -> String,
    )

    fun captureException(type: String, message: String, stack: String)
}
