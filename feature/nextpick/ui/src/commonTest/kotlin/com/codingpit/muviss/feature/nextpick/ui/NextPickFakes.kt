package com.codingpit.muviss.feature.nextpick.ui

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.feature.nextpick.domain.CandidateSource
import com.codingpit.muviss.feature.nextpick.domain.CompletionPromptStore
import com.codingpit.muviss.feature.nextpick.domain.NextPickActions
import com.codingpit.muviss.feature.nextpick.domain.PickCandidate
import com.codingpit.muviss.feature.nextpick.domain.PickCandidates
import com.codingpit.muviss.feature.nextpick.domain.PickEngine
import com.codingpit.muviss.feature.nextpick.domain.PickExclusions
import com.codingpit.muviss.feature.nextpick.domain.PickHistory
import com.codingpit.muviss.feature.nextpick.domain.PickHistoryReader
import com.codingpit.muviss.feature.nextpick.domain.PickMuting
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MutedGenres
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

internal fun candidate(id: String, title: String = "Title $id"): PickCandidate = PickCandidate(
    summary = MediaSummary(id = MediaId.tmdbMovie(id), title = title),
    source = CandidateSource.DISCOVER,
)

/** An engine over a fixed pool, so a ViewModel test controls exactly what the session can pick. */
internal fun engineOver(pool: List<PickCandidate>): PickEngine = PickEngine(
    sources = listOf(PickCandidates { _, _ -> pool }),
    exclusions = PickExclusions { emptySet() },
    history = PickHistoryReader { PickHistory() },
    muting = PickMuting { MutedGenres.NONE },
)

/** Records each write. [hold] keeps writes in flight until completed; [fail] makes them throw instead. */
internal class RecordingNextPickActions : NextPickActions {
    val added = mutableListOf<MediaId>()
    val refused = mutableListOf<MediaId>()
    val snoozed = mutableListOf<MediaId>()
    var hold: CompletableDeferred<Unit>? = null
    var fail = false

    override suspend fun add(summary: MediaSummary) = write { added += summary.id }

    override suspend fun notForMe(summary: MediaSummary) = write { refused += summary.id }

    override suspend fun snooze(summary: MediaSummary) = write { snoozed += summary.id }

    private suspend fun write(record: () -> Unit) {
        hold?.await()
        check(!fail) { "write failed" }
        record()
    }
}

internal class FixedClock(private val nowMs: Long) : AppClock {
    override fun nowEpochMs(): Long = nowMs
}

/** [failingReads] is how many `lastShownAtEpochMs` calls throw before the store answers. */
internal class FakePromptStore(enabled: Boolean = true, lastShown: Long? = null, private var failingReads: Int = 0) : CompletionPromptStore {
    override val enabled: Flow<Boolean> = flowOf(enabled)
    private val shownAt = MutableStateFlow(lastShown)
    val marks = mutableListOf<Long>()

    override suspend fun lastShownAtEpochMs(): Long? {
        check(failingReads-- <= 0) { "store read failed" }
        return shownAt.first()
    }

    override suspend fun markShown(atEpochMs: Long) {
        marks += atEpochMs
        shownAt.value = atEpochMs
    }
}
