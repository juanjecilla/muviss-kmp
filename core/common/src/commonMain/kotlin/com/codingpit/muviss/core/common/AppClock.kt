package com.codingpit.muviss.core.common

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Wall-clock time, abstracted so it can be faked in tests (e.g. asserting
 * `addedAtEpochMs`/`updatedAtEpochMs` on a saved [collection entry]
 * [com.codingpit.muviss.feature.collection.domain.CollectionEntry]).
 */
interface AppClock {
    fun nowEpochMs(): Long
}

@OptIn(ExperimentalTime::class)
class SystemClock : AppClock {
    override fun nowEpochMs(): Long = Clock.System.now().toEpochMilliseconds()
}

private const val MILLIS_PER_DAY = 86_400_000L

/**
 * Today as an epoch day (days since 1970-01-01 UTC), matching the convention
 * used by [com.codingpit.muviss.models.Episode.airDateEpochDay] for "has this
 * episode aired yet" comparisons.
 */
fun AppClock.todayEpochDay(): Long = nowEpochMs() / MILLIS_PER_DAY
