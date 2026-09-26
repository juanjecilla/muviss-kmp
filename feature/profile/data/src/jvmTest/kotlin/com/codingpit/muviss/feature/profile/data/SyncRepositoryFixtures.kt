package com.codingpit.muviss.feature.profile.data

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.flags.FeatureFlags
import com.codingpit.muviss.core.common.flags.SnoozePeriod
import com.codingpit.muviss.core.common.flags.SnoozePlacement
import com.codingpit.muviss.core.common.flags.TriageControlScheme
import com.codingpit.muviss.core.sync.SyncCoordinator
import com.codingpit.muviss.core.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/** A [FeatureFlags] over plain state, for tests of the classes that read or write the sync switch. */
internal class FakeFeatureFlags(automaticSync: Boolean = false) : FeatureFlags {
    val automaticSyncState = MutableStateFlow(automaticSync)

    override val triageControlScheme: Flow<TriageControlScheme> = flowOf(TriageControlScheme.DEFAULT)
    override val animationsEnabled: Flow<Boolean> = flowOf(true)
    override val triageDeckAnimations: Flow<Boolean> = flowOf(true)
    override val syncAutomatically: Flow<Boolean> = automaticSyncState
    override val triageSnoozePeriod: Flow<SnoozePeriod> = flowOf(SnoozePeriod.DEFAULT)
    override val triageSnoozePlacement: Flow<SnoozePlacement> = flowOf(SnoozePlacement.DEFAULT)

    override suspend fun setTriageControlScheme(scheme: TriageControlScheme) = Unit

    override suspend fun setAnimationsEnabled(enabled: Boolean) = Unit

    override suspend fun setTriageDeckAnimations(enabled: Boolean) = Unit

    override suspend fun setSyncAutomatically(enabled: Boolean) {
        automaticSyncState.value = enabled
    }

    override suspend fun setTriageSnoozePeriod(period: SnoozePeriod) = Unit

    override suspend fun setTriageSnoozePlacement(placement: SnoozePlacement) = Unit
}

/** A coordinator that watches nothing (no dirty rows, never enabled), for tests that are not about it. */
internal fun idleCoordinator(engine: SyncEngine, clock: AppClock, scope: CoroutineScope) = SyncCoordinator(
    runner = engine,
    pendingChanges = flowOf(0L),
    automaticEnabled = flowOf(false),
    clock = clock,
    scope = scope,
)
