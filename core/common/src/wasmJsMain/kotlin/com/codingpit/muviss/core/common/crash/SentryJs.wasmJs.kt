@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.codingpit.muviss.core.common.crash

/**
 * [SentryJs]'s `wasmJs` actual. A Kotlin function type crossing the `js(...)`
 * boundary here (see below) is still marked [kotlin.js.ExperimentalWasmJsInterop]
 * on this target — the opt-in above, rather than suppressing the warning,
 * since `kotlin.js.ExperimentalWasmJsInterop` on the plain `js` target would
 * be an unnecessary opt-in there instead (confirmed: the `js` actual compiles
 * clean without it), one more of the small differences this file and
 * `SentryJs.js.kt` accept for the reasons `WebCrashBackend.kt` explains,
 * otherwise byte-for-byte the same code — kept in step deliberately rather
 * than merged, the same choice `DatabaseFactory.js.kt`/`DatabaseFactory.wasmJs.kt`
 * already made.
 *
 * `document`/`window` are referenced only *inside* the `js(...)` strings
 * below, as bare global identifiers — true in any browser main-thread
 * context regardless of a Kotlin import — rather than through
 * `kotlinx.browser`, so there is nothing to import. Plain Kotlin function
 * types (`() -> Unit`, `(String) -> String`) cross the `js(...)` boundary as
 * directly-callable JS functions on both js and wasmJs — the compiler wraps
 * them — so there is no need for a SAM-convertible `external` interface the
 * way `org.w3c.dom.EventListener` is; that type predates this and a plain
 * function type is the more direct route where, as here, nothing else needs
 * the wrapper type's name.
 */
internal actual object SentryJs {
    actual fun load(url: String, onLoad: () -> Unit) {
        injectScript(url, onLoad)
    }

    @Suppress("LongParameterList") // mirrors CrashReportingConfig's four fields plus two callbacks
    actual fun init(
        dsn: String,
        environment: String,
        release: String,
        dist: String,
        isEnabled: () -> Boolean,
        scrub: (String) -> String,
    ) {
        sentryInit(dsn, environment, release, dist, isEnabled, scrub)
    }

    actual fun captureException(type: String, message: String, stack: String) {
        sentryCaptureException(type, message, stack)
    }
}

@Suppress("UnusedParameter")
private fun injectScript(url: String, onLoad: () -> Unit): Unit = js(
    """(function () {
        var s = document.createElement("script");
        s.src = url;
        s.crossOrigin = "anonymous";
        s.onload = function () { onLoad(); };
        document.head.appendChild(s);
    })()""",
)

/**
 * `dataCollection` (rather than the older `sendDefaultPii` boolean some
 * Sentry SDKs still use) is `@sentry/browser` 11's own option for this —
 * checked against its published `.d.ts` on 2026-09-29. `userInfo: false`
 * matches `SentryBackend.kt`'s `sendDefaultPii = false`; `cookies`,
 * `httpHeaders` and `urlQueryParams` are turned off on top of it because
 * this SDK version's automatic fetch/XHR breadcrumbs would otherwise attach
 * the TMDB key riding along as a `?api_key=` query parameter (the exact leak
 * `CrashScrubber` exists for) before `beforeBreadcrumb` even runs.
 *
 * `event`/`breadcrumb` fields scrubbed below mirror `SentryBackend.scrubbed()`
 * (`SentryBackend.kt`) field-for-field — `message`, `exception[].value`,
 * `tags`, string-valued `contexts`, plus `request.url` and string-valued
 * `extra`, which that file's KDoc notes the Kotlin Multiplatform SDK's event
 * type does not even expose; this SDK's does, so they are covered too.
 */
@Suppress("UnusedParameter", "LongParameterList")
private fun sentryInit(
    dsn: String,
    environment: String,
    release: String,
    dist: String,
    isEnabled: () -> Boolean,
    scrub: (String) -> String,
): Unit = js(
    """(function () {
        function scrubText(v) { return (typeof v === "string") ? scrub(v) : v; }
        function scrubStringValues(o) {
            if (!o) return;
            Object.keys(o).forEach(function (k) {
                if (typeof o[k] === "string") o[k] = scrubText(o[k]);
            });
        }
        function scrubEvent(event) {
            if (event.message) event.message = scrubText(event.message);
            if (event.exception && event.exception.values) {
                event.exception.values.forEach(function (ex) {
                    if (ex.value) ex.value = scrubText(ex.value);
                });
            }
            if (event.request && event.request.url) event.request.url = scrubText(event.request.url);
            scrubStringValues(event.tags);
            scrubStringValues(event.contexts);
            scrubStringValues(event.extra);
            return event;
        }
        window.Sentry.init({
            dsn: dsn,
            environment: environment,
            release: release,
            dist: dist,
            dataCollection: { userInfo: false, cookies: false, httpHeaders: false, urlQueryParams: false },
            beforeSend: function (event) {
                return isEnabled() ? scrubEvent(event) : null;
            },
            beforeBreadcrumb: function (breadcrumb) {
                if (breadcrumb.message) breadcrumb.message = scrubText(breadcrumb.message);
                scrubStringValues(breadcrumb.data);
                return breadcrumb;
            },
        });
    })()""",
)

/**
 * Kotlin/JS's `Throwable` never crosses this boundary — [WebCrashBackend]
 * hands over [type]/[message]/[stack] as plain, already-scrubbed strings
 * (see `CrashScrubber`), and this builds a synthetic `Error` from them so
 * Sentry's stack parser has the `Name: message\n    at ...` shape it expects.
 * That keeps this function identical to the wasmJs actual, where a Kotlin
 * `Throwable` is a Wasm GC object with no JS `Error` representation to pass
 * across at all.
 */
@Suppress("UnusedParameter")
private fun sentryCaptureException(type: String, message: String, stack: String): Unit = js(
    """(function () {
        var e = new Error(message);
        e.name = type;
        e.stack = type + ": " + message + "\n" + stack;
        window.Sentry.captureException(e);
    })()""",
)
