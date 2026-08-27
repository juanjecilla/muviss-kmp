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

    /**
     * The app-wide motion switch. Off means every animation the app plays on
     * purpose should be skipped, not shortened — a transition either happens or
     * the end state appears immediately.
     *
     * Direct manipulation is not an animation and is out of scope: a card
     * tracking a finger keeps tracking it with this off, because that motion
     * *is* the gesture rather than a decoration on top of it.
     */
    val animationsEnabled: Flow<Boolean>

    /**
     * The triage deck's own card animations — the committed card's fly-out, the
     * undo re-entry, and the stack promoting behind it.
     *
     * Narrows [animationsEnabled] rather than competing with it: readers take
     * the AND of the two, so the master switch turns this off regardless. It
     * exists because the deck is the one screen that throws a card clear across
     * the display, and someone can want that gone without flattening the rest
     * of the app.
     */
    val triageDeckAnimations: Flow<Boolean>

    suspend fun setTriageControlScheme(scheme: TriageControlScheme)

    suspend fun setAnimationsEnabled(enabled: Boolean)

    suspend fun setTriageDeckAnimations(enabled: Boolean)
}
