package com.codingpit.muviss.core.testing

import com.codingpit.muviss.core.common.flags.FeatureFlags
import com.codingpit.muviss.core.common.flags.SnoozePeriod
import com.codingpit.muviss.core.common.flags.SnoozePlacement
import com.codingpit.muviss.core.common.flags.TriageControlScheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A [FeatureFlags] over plain state, for tests that only need *a* set of flags.
 *
 * The suites that predate it (settings ui, triage ui, profile data, `NoFlags`) each carry their
 * own copy, and a new flag breaks every one of them (CLAUDE.md). Use this one instead of
 * writing another: a new flag is then one edit here.
 */
class FakeFeatureFlags(byokEnabled: Boolean = false) : FeatureFlags {
    override val triageControlScheme = MutableStateFlow(TriageControlScheme.DEFAULT)
    override val animationsEnabled = MutableStateFlow(true)
    override val triageDeckAnimations = MutableStateFlow(true)
    override val tickAnimations = MutableStateFlow(true)
    override val syncAutomatically = MutableStateFlow(false)
    override val byokEnabled = MutableStateFlow(byokEnabled)
    override val triageSnoozePeriod = MutableStateFlow(SnoozePeriod.DEFAULT)
    override val triageSnoozePlacement = MutableStateFlow(SnoozePlacement.DEFAULT)

    override suspend fun setTriageControlScheme(scheme: TriageControlScheme) {
        triageControlScheme.value = scheme
    }

    override suspend fun setAnimationsEnabled(enabled: Boolean) {
        animationsEnabled.value = enabled
    }

    override suspend fun setTriageDeckAnimations(enabled: Boolean) {
        triageDeckAnimations.value = enabled
    }

    override suspend fun setTickAnimations(enabled: Boolean) {
        tickAnimations.value = enabled
    }

    override suspend fun setSyncAutomatically(enabled: Boolean) {
        syncAutomatically.value = enabled
    }

    override suspend fun setTriageSnoozePeriod(period: SnoozePeriod) {
        triageSnoozePeriod.value = period
    }

    override suspend fun setTriageSnoozePlacement(placement: SnoozePlacement) {
        triageSnoozePlacement.value = placement
    }
}
