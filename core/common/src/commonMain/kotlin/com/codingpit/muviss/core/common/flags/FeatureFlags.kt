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
 * How long a Snooze waits before the deck raises the title again (EPIC 42,
 * ADR 0023).
 *
 * [ASK_EACH_TIME] is not a duration. It means the app asks on every snooze
 * instead of applying a stored one, and it is the only route to a freely
 * chosen date — the presets exist so the common case costs one press.
 */
enum class SnoozePeriod {
    ONE_WEEK,
    ONE_MONTH,
    THREE_MONTHS,
    ASK_EACH_TIME,
    ;

    /** Days to add to today. Null for [ASK_EACH_TIME], which has no duration of its own. */
    val days: Long?
        get() = when (this) {
            ONE_WEEK -> 7L
            ONE_MONTH -> 30L
            THREE_MONTHS -> 90L
            ASK_EACH_TIME -> null
        }

    companion object {
        val DEFAULT: SnoozePeriod = ONE_WEEK

        /** Tolerates anything unrecognised in storage — a flag must never crash the app. */
        fun fromStored(raw: String?): SnoozePeriod = entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}

/**
 * Where a Snooze that has come due re-enters the deck (EPIC 42, ADR 0023).
 *
 * [MIXED_IN] caps how many due titles any one batch may carry, so a long
 * absence drains over several batches instead of burying discovery — the
 * "tidied-up library resurfacing card by card" failure ADR 0010 exists to
 * prevent. [FIRST] and [LAST] are for people who would rather clear them in
 * one go, or never be interrupted by them.
 */
enum class SnoozePlacement {
    MIXED_IN,
    FIRST,
    LAST,
    ;

    companion object {
        val DEFAULT: SnoozePlacement = MIXED_IN

        /** Tolerates anything unrecognised in storage — a flag must never crash the app. */
        fun fromStored(raw: String?): SnoozePlacement = entries.firstOrNull { it.name == raw } ?: DEFAULT
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

    /**
     * Whether this device may sync without being asked (EPIC 40, ADR 0021).
     * Off by default. Per device and never synced: it is a statement about
     * *this* device's battery and data plan, not about the library.
     *
     * Only one of three layers: the build must ship background sync
     * (`SyncAvailability.isBackgroundAvailable`) and there must be a session and
     * an entitlement. `SyncEngine` enforces all of them, so reading this flag
     * elsewhere is for presentation, never for permission.
     */
    val syncAutomatically: Flow<Boolean>

    /**
     * How long a Snooze waits before the deck raises the title again. A
     * per-device preference and never synced: the Snoozes themselves are user
     * data and cross devices, but how patient *this* device is does not.
     */
    val triageSnoozePeriod: Flow<SnoozePeriod>

    /** Where a due Snooze re-enters the deck. Per device, like the period. */
    val triageSnoozePlacement: Flow<SnoozePlacement>

    suspend fun setTriageControlScheme(scheme: TriageControlScheme)

    suspend fun setAnimationsEnabled(enabled: Boolean)

    suspend fun setTriageDeckAnimations(enabled: Boolean)

    suspend fun setSyncAutomatically(enabled: Boolean)

    suspend fun setTriageSnoozePeriod(period: SnoozePeriod)

    suspend fun setTriageSnoozePlacement(placement: SnoozePlacement)
}
