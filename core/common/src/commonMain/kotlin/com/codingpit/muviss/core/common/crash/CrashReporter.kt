package com.codingpit.muviss.core.common.crash

import kotlin.concurrent.Volatile

/**
 * Everything the Sentry SDK needs to be told about *this* build, so an event can
 * be matched to the R8 mapping uploaded for it.
 *
 * [release] and [dist] must be the strings the Sentry Gradle plugin stamps on
 * the mapping (`<applicationId>@<versionName>+<versionCode>` and the
 * `versionCode`), or Sentry has no way to know which mapping deobfuscates which
 * trace — see `docs/RELEASING.md`. A blank [dsn] means "not configured": the
 * default for every local and CI build, which then never touches Sentry at all.
 */
data class CrashReportingConfig(
    val dsn: String,
    val environment: String,
    val release: String,
    val dist: String,
)

/**
 * Seam for crash/error reporting (Sentry Kotlin Multiplatform SDK on
 * Android/iOS/JVM; a deliberate no-op on JS/Wasm, stated in `docs/PRIVACY.md`).
 *
 * The three rules that make this safe to call from anywhere:
 *
 * - **[init] is idempotent.** The first call with a usable DSN starts the SDK;
 *   every later one does nothing. It has to be, because each host now starts
 *   reporting itself, *before* Koin (a crash while the graph is being built is
 *   the one you most want to see), and `MuvissApp()` still calls it as a guard.
 * - **Consent is a runtime switch, not a build-time decision.** [setEnabled]
 *   flips a gate that every outgoing event passes through, so turning the
 *   Settings toggle off takes effect at once, with no restart. See
 *   [CrashReportGate].
 * - **Nothing here throws.** A reporter that can crash the app it is reporting
 *   on is worse than none.
 */
object CrashReporter {
    private val core = CrashReporterCore(platformCrashBackend())

    /**
     * Starts the SDK unless it already is. [enabled] is the stored consent, and
     * only the call that actually starts the SDK gets to set it: a later call is
     * the `MuvissApp()` guard, which has no consent to offer and must not
     * overrule the one the host read.
     */
    fun init(config: CrashReportingConfig, enabled: Boolean) = core.init(config, enabled)

    fun setEnabled(enabled: Boolean) = core.setEnabled(enabled)

    /**
     * Whether this platform has a real reporter. False on web, where the Settings
     * toggle would change nothing, so the screen does not offer it.
     */
    val isAvailable: Boolean get() = core.isAvailable

    fun recordException(throwable: Throwable) = core.recordException(throwable)
}

/**
 * The behaviour behind [CrashReporter], separated from the singleton so a test
 * can give it a fake [CrashBackend] and count what reaches the SDK.
 */
internal class CrashReporterCore(private val backend: CrashBackend) {
    private val gate = CrashReportGate()

    @Volatile
    private var started = false

    fun init(config: CrashReportingConfig, enabled: Boolean) {
        if (started || config.dsn.isBlank()) return
        gate.enabled = enabled
        // Marked before the SDK is asked, so a backend that itself calls back
        // in (or a second host thread) cannot start it twice.
        started = true
        runCatching { backend.start(config, gate) }
    }

    val isAvailable: Boolean get() = backend.isAvailable

    fun setEnabled(enabled: Boolean) {
        gate.enabled = enabled
    }

    fun recordException(throwable: Throwable) {
        if (!started || !gate.enabled) return
        runCatching { backend.capture(throwable) }
    }
}

/**
 * Whether the person has allowed crash reports. Read by the SDK's `beforeSend`
 * for *every* event — handled exceptions and real crashes alike — so consent is
 * enforced in one place rather than at each call site that might report.
 */
internal class CrashReportGate {
    @Volatile
    var enabled: Boolean = true
}

/** The platform's Sentry, behind the smallest surface [CrashReporterCore] needs. */
internal interface CrashBackend {
    /** False for the web no-op. */
    val isAvailable: Boolean get() = true

    fun start(config: CrashReportingConfig, gate: CrashReportGate)

    fun capture(throwable: Throwable)
}

internal expect fun platformCrashBackend(): CrashBackend
