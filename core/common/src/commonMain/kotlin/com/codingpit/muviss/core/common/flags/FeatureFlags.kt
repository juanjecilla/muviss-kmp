package com.codingpit.muviss.core.common.flags

import kotlinx.coroutines.flow.Flow

/**
 * Which drag scheme the triage deck uses (ADR 0010).
 *
 * [FOUR_WAY] maps all four verdicts onto directions (left = Skip, right =
 * Later, up = Caught up, down = Watching). [THREE_WAY] keeps the first three
 * and leaves Watching to its button, trading reach for fewer mis-hits on the
 * least discoverable direction. Both ship; neither is a temporary experiment
 * arm, because nothing in this app measures which one wins — see
 * [AnalyticsTracker][com.codingpit.muviss.core.common.analytics.AnalyticsTracker].
 */
enum class TriageControlScheme {
    FOUR_WAY,
    THREE_WAY,
    ;

    companion object {
        val DEFAULT: TriageControlScheme = FOUR_WAY

        /** Tolerates anything unrecognised in storage — a flag must never crash the app. */
        fun fromStored(raw: String?): TriageControlScheme = entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}

/**
 * Seam for runtime feature flags.
 *
 * The only implementation today reads the local `appSettings` row, so every
 * flag is a per-device preference the user sets in Settings. It is declared as
 * an interface here — rather than being folded into `SettingsApi` — so that a
 * remote configuration source can be bound in its place later without any
 * caller changing, exactly as `MetadataProvider` (ADR 0001) and `SyncBackend`
 * (ADR 0009) abstract their vendors.
 */
interface FeatureFlags {
    val triageControlScheme: Flow<TriageControlScheme>

    suspend fun setTriageControlScheme(scheme: TriageControlScheme)
}
