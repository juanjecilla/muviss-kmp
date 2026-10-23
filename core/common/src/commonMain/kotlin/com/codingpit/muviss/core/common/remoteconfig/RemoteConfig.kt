package com.codingpit.muviss.core.common.remoteconfig

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What Remote Config can currently say (EPIC 45, ADR 0026). Every field has a
 * compiled default, so a value always exists: offline, before the first fetch,
 * and in a build with no ConfigCat key at all.
 *
 * @property byokEnabled whether the app *offers* to take an Own TMDB Key. It
 *   never removes a stored key (ADR 0027).
 * @property tmdbFallbackReadToken a TMDB v4 read token served in place of the
 *   one built into the binary, or null to use the built-in one. The point is
 *   rotation, not secrecy: it is no more secret than the value in the APK.
 * @property featureRequestProvider which hosted form service the form URLs point at, or
 *   [FeatureRequestProvider.Off] to hide the Feature Request and Bug Report rows. It decides
 *   how a link carries its hidden fields, because Tally and Typeform read them differently;
 *   both forms live with the same provider.
 * @property featureRequestUrl the public URL of the Feature Request form, or null when
 *   there is none yet. A null or blank URL hides the row whatever the provider says.
 * @property bugReportUrl the same for the Bug Report form (ADR 0035, #449).
 */
data class RemoteValues(
    val byokEnabled: Boolean,
    val tmdbFallbackReadToken: String? = null,
    val featureRequestProvider: FeatureRequestProvider = FeatureRequestProvider.Tally,
    val featureRequestUrl: String? = null,
    val bugReportUrl: String? = null,
)

/** The hosted form service behind the Feature Request row. Stored by [key]. */
enum class FeatureRequestProvider(val key: String) {
    Tally("tally"),
    Typeform("typeform"),
    Off("off"),
    ;

    companion object {
        /** An unknown or blank served value reads as [Tally], the compiled default, never as [Off]. */
        fun fromStored(value: String?): FeatureRequestProvider = entries.firstOrNull { it.key.equals(value?.trim(), ignoreCase = true) } ?: Tally
    }
}

/**
 * Seam for values the app must be able to change without a release. Read-only,
 * and deliberately not folded into [com.codingpit.muviss.core.common.flags.FeatureFlags]:
 * those are per-device preferences the user writes, these are served to every
 * install and nothing local can change them.
 *
 * Nothing outside the vendor module knows ConfigCat. No user identifier is
 * ever passed to it; evaluation is local, so the vendor learns only that a
 * device fetched the config.
 */
interface RemoteConfig {
    /** The current values: the compiled defaults until the stored copy is read, then the last good config. */
    val values: StateFlow<RemoteValues>

    /**
     * Suspends until the *stored* copy has been read — a single local read, never the
     * network — and returns the values. Anything that must honour a rotated value on
     * the first request after a cold start (the TMDB credential) awaits this instead
     * of reading [values], which may still hold the compiled defaults.
     */
    suspend fun awaitStored(): RemoteValues

    /**
     * Fetches a fresh config unless one was fetched recently. Never throws and never
     * blocks the caller for longer than the request; a failure keeps the last values.
     * Call it off the main thread, at startup and on foreground.
     */
    suspend fun refreshIfStale()
}

/** [RemoteConfig] that serves only [defaults] and never touches the network. Bound when the build has no vendor key. */
class DefaultsRemoteConfig(private val defaults: RemoteValues) : RemoteConfig {
    override val values: StateFlow<RemoteValues> = MutableStateFlow(defaults)

    override suspend fun awaitStored(): RemoteValues = defaults

    override suspend fun refreshIfStale() = Unit
}
