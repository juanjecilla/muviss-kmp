package com.codingpit.muviss.core.sync

/**
 * The first-run value of each Display Preference, as a fresh `appSettings` row holds it (ADR 0036).
 *
 * "Changed" means "differs from this", and the merge of a dirty group keeps only changed members
 * (see `RemoteApplier`). `DisplayPreferenceDefaultsTest` pins these against `ensureRow` and the column
 * defaults in `AppSettings.sq`, so a change to one side fails there rather than silently here.
 */
internal object DisplayPreferenceDefaults {
    const val THEME = "SYSTEM"
    const val LANGUAGE = ""
    const val REGION = "US"
    const val ANIMATIONS_ENABLED = true
    const val TRIAGE_DECK_ANIMATIONS = true
    const val TICK_ANIMATIONS = true
    const val TRIAGE_CONTROL_SCHEME = "FOUR_WAY"
    const val TRIAGE_SNOOZE_PERIOD = "ONE_WEEK"
    const val TRIAGE_SNOOZE_PLACEMENT = "MIXED_IN"
    const val MUTED_GENRES_IN_SEARCH = false
}

/** The ten Display Preferences as one value, the shape the group's rows and `appSettings` share. */
internal data class DisplayPreferencesGroup(
    val theme: String,
    val language: String,
    val region: String,
    val animationsEnabled: Boolean,
    val triageDeckAnimations: Boolean,
    val tickAnimations: Boolean,
    val triageControlScheme: String,
    val triageSnoozePeriod: String,
    val triageSnoozePlacement: String,
    val mutedGenresInSearch: Boolean,
)
