package com.codingpit.muviss.feature.nextpick.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.crash.launchReporting
import com.codingpit.muviss.feature.nextpick.domain.NextPickActions
import com.codingpit.muviss.feature.nextpick.domain.PickAnswer
import com.codingpit.muviss.feature.nextpick.domain.PickContext
import com.codingpit.muviss.feature.nextpick.domain.PickEngine
import com.codingpit.muviss.feature.nextpick.domain.PickQuestion
import com.codingpit.muviss.feature.nextpick.domain.PickResult
import com.codingpit.muviss.feature.nextpick.domain.PickSession
import com.codingpit.muviss.feature.nextpick.domain.RankedPick
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the screen shows. A pick is never a status: the three outcomes only say what the person did with it. */
sealed interface PickSessionUiState {
    data object Loading : PickSessionUiState

    /** One question at a time. [step] is 1-based, out of [total]. */
    data class Asking(
        val question: PickQuestion,
        val step: Int,
        val total: Int,
        val offline: Boolean,
        val canGoBack: Boolean,
        /** The title the session is about when it was opened from a Completion; "more like it" is about this one (#548). */
        val anchor: MediaId? = null,
    ) : PickSessionUiState

    /** The pick, with [outcome] once the person has added, refused or snoozed it. */
    data class Result(val pick: RankedPick, val offline: Boolean, val outcome: PickOutcome?) : PickSessionUiState

    /** The rules found nothing that fits; the screen says so and offers to start again. */
    data class NoPick(val offline: Boolean) : PickSessionUiState

    data object Failed : PickSessionUiState
}

enum class PickOutcome { ADDED, NOT_FOR_ME, SNOOZED }

/**
 * Drives one Pick Session (EPIC 51, #400): the domain [PickSession] asks, the [PickEngine] picks, and the
 * result is handed to whichever presentation the screen draws. Answers and picks live here only; nothing
 * is written except through [NextPickActions], which are the existing Add, Skip and Snooze writes.
 *
 * [context] is read by the caller once per session, so the ViewModel stays free of platform types.
 */
class PickSessionViewModel(
    private val context: suspend () -> PickContext,
    private val engine: PickEngine,
    private val actions: NextPickActions,
    /** The title a Completion just finished, when the session was opened from one (#548). Null otherwise. */
    private val anchor: MediaId? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<PickSessionUiState>(PickSessionUiState.Loading)
    val state: StateFlow<PickSessionUiState> = _state.asStateFlow()

    private var readContext: PickContext? = null
    private var session: PickSession? = null
    private val previous = ArrayDeque<PickSession>()
    private val shown = mutableSetOf<MediaId>()
    private var current: RankedPick? = null

    /**
     * The engine's ranked pool for the current answers, read once. "Another" takes the next pick from it, so
     * it costs no new TMDB requests (#548). Dropped whenever the answers or the criteria change.
     */
    private var pool: PickResult? = null

    init {
        start()
    }

    /** Starts a fresh session, reading the context again. Used on open and by "Start again". */
    fun start() {
        viewModelScope.launchReporting {
            _state.value = PickSessionUiState.Loading
            val read = context()
            val opened = read.withAnchor()
            readContext = opened
            session = PickSession.start(opened)
            previous.clear()
            shown.clear()
            current = null
            pool = null
            advance()
        }
    }

    fun answer(answer: PickAnswer) = step { it.answer(answer) }

    fun skipQuestion() = step { it.skip() }

    /** Goes back one question. Returns false when there is none, so the screen can leave instead. */
    fun back(): Boolean {
        val last = previous.removeLastOrNull() ?: return false
        session = last
        current = null
        pool = null
        viewModelScope.launchReporting { advance() }
        return true
    }

    /** The "another" button: the next-best pick for the same answers, never one already offered. */
    fun another() {
        if (session == null) return
        viewModelScope.launchReporting { pickNext() }
    }

    fun add() = act(PickOutcome.ADDED) { actions.add(it.candidate.summary) }

    fun notForMe() = act(PickOutcome.NOT_FOR_ME) { actions.notForMe(it.candidate.summary) }

    fun snooze() = act(PickOutcome.SNOOZED) { actions.snooze(it.candidate.summary) }

    private fun step(next: (PickSession) -> PickSession) {
        val now = session ?: return
        previous.addLast(now)
        session = next(now)
        pool = null
        viewModelScope.launchReporting { advance() }
    }

    private suspend fun advance() {
        val live = session ?: return
        val read = readContext ?: return
        val question = live.question
        if (question != null) {
            _state.value = PickSessionUiState.Asking(
                question = question,
                step = live.asked + 1,
                total = read.length.maxQuestions,
                offline = !read.online,
                canGoBack = previous.isNotEmpty(),
                anchor = read.anchorCandidate,
            )
        } else {
            pickNext()
        }
    }

    private suspend fun pickNext() {
        val live = session ?: return
        val read = readContext ?: return
        try {
            val result = pool ?: engine.pick(live.criteria, read, limit = POOL_SIZE).also { pool = it }
            val next = result.picks.firstOrNull { it.candidate.id !in shown }
            if (next == null) {
                current = null
                _state.value = PickSessionUiState.NoPick(offline = result.offline)
            } else {
                shown += next.candidate.id
                current = next
                _state.value = PickSessionUiState.Result(next, result.offline, outcome = null)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) { // Any failure ends the session visibly; rethrown so the crash reporter still gets it.
            _state.value = PickSessionUiState.Failed
            throw failure
        }
    }

    /** The context the session runs on: the caller's, with the anchor it was opened from (#548). */
    private fun PickContext.withAnchor(): PickContext = anchor?.let { copy(anchorCandidate = it) } ?: this

    private fun act(outcome: PickOutcome, write: suspend (RankedPick) -> Unit) {
        val pick = current ?: return
        val offline = (_state.value as? PickSessionUiState.Result)?.offline ?: false
        viewModelScope.launchReporting {
            write(pick)
            _state.value = PickSessionUiState.Result(pick, offline, outcome)
        }
    }
}

/**
 * How many ranked picks one gather keeps for "Another". The engine's [PickEngine.DEFAULT_LIMIT] is what one pick
 * shows; this is how far "Another" can go before the answers have to change. The TMDB cost is the same at any limit.
 */
private const val POOL_SIZE = 20
