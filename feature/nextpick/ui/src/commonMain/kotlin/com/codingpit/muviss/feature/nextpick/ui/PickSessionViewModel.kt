package com.codingpit.muviss.feature.nextpick.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.crash.launchReporting
import com.codingpit.muviss.feature.nextpick.domain.DefaultNextPickSettings
import com.codingpit.muviss.feature.nextpick.domain.NextPickActions
import com.codingpit.muviss.feature.nextpick.domain.NextPickResultMode
import com.codingpit.muviss.feature.nextpick.domain.NextPickSettings
import com.codingpit.muviss.feature.nextpick.domain.PickAnswer
import com.codingpit.muviss.feature.nextpick.domain.PickContext
import com.codingpit.muviss.feature.nextpick.domain.PickEngine
import com.codingpit.muviss.feature.nextpick.domain.PickQuestion
import com.codingpit.muviss.feature.nextpick.domain.PickSession
import com.codingpit.muviss.feature.nextpick.domain.RankedPick
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
    ) : PickSessionUiState

    /**
     * The picks on screen, in rank order, drawn as [mode] says. Each row has its own [outcomes] entry once the
     * person has added, refused or snoozed it.
     */
    data class Result(
        val mode: NextPickResultMode,
        val picks: List<RankedPick>,
        val offline: Boolean,
        val outcomes: Map<MediaId, PickOutcome> = emptyMap(),
    ) : PickSessionUiState

    /** The rules found nothing that fits; the screen says so and offers to start again. */
    data object NoPick : PickSessionUiState

    data object Failed : PickSessionUiState
}

enum class PickOutcome { ADDED, NOT_FOR_ME, SNOOZED }

/**
 * Drives one Pick Session (EPIC 51, #400, #401): the domain [PickSession] asks, the [PickEngine] picks, and the
 * result is handed to whichever presentation the mode names. Answers and picks live here only; nothing is
 * written except through [NextPickActions], which are the existing Add, Skip and Snooze writes.
 *
 * The mode is read once per session, in [start], so a change in Settings applies to the next session and never
 * reshuffles one that is on screen.
 *
 * [context] is read by the caller once per session, so the ViewModel stays free of platform types.
 */
class PickSessionViewModel(
    private val context: suspend () -> PickContext,
    private val engine: PickEngine,
    private val actions: NextPickActions,
    private val settings: NextPickSettings = DefaultNextPickSettings,
) : ViewModel() {

    private val _state = MutableStateFlow<PickSessionUiState>(PickSessionUiState.Loading)
    val state: StateFlow<PickSessionUiState> = _state.asStateFlow()

    private var readContext: PickContext? = null
    private var session: PickSession? = null
    private val previous = ArrayDeque<PickSession>()
    private var mode: NextPickResultMode = DefaultNextPickSettings.mode

    /** Every title already drawn this session, so "another" and a refill never repeat one. */
    private val shown = mutableSetOf<MediaId>()

    init {
        start()
    }

    /** Starts a fresh session, reading the context and the result mode again. Used on open and by "Start again". */
    fun start() {
        viewModelScope.launchReporting {
            _state.value = PickSessionUiState.Loading
            val read = context()
            readContext = read
            mode = settings.mode
            session = PickSession.start(read)
            previous.clear()
            shown.clear()
            advance()
        }
    }

    fun answer(answer: PickAnswer) = step { it.answer(answer) }

    fun skipQuestion() = step { it.skip() }

    /** Goes back one question. Returns false when there is none, so the screen can leave instead. */
    fun back(): Boolean {
        val last = previous.removeLastOrNull() ?: return false
        session = last
        viewModelScope.launchReporting { advance() }
        return true
    }

    /**
     * The "another" button: the next picks for the same answers, never one already drawn. The count drawn is the
     * mode's [NextPickResultMode.pickCount], so One Pick steps to the next single title.
     */
    fun another() {
        if (session == null) return
        viewModelScope.launchReporting { pickNext() }
    }

    fun add(id: MediaId) = act(id, PickOutcome.ADDED) { actions.add(it.candidate.summary) }

    fun notForMe(id: MediaId) = act(id, PickOutcome.NOT_FOR_ME) { actions.notForMe(it.candidate.summary) }

    fun snooze(id: MediaId) = act(id, PickOutcome.SNOOZED) { actions.snooze(it.candidate.summary) }

    /**
     * The titles the Deck may take: picks the library does not hold. Triage's undo removes a collection entry and
     * its ticks, and Triage excludes what is already in the library, so a backlog title could not be swiped there.
     */
    fun deckSeeds(): List<MediaSummary> = (_state.value as? PickSessionUiState.Result)?.picks.orEmpty()
        .filter { it.candidate.libraryStatus == null }
        .map { it.candidate.summary }

    private fun step(next: (PickSession) -> PickSession) {
        val now = session ?: return
        previous.addLast(now)
        session = next(now)
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
            )
        } else {
            pickNext()
        }
    }

    private suspend fun pickNext() {
        val live = session ?: return
        val read = readContext ?: return
        val count = mode.pickCount
        try {
            val result = engine.pick(live.criteria, read, limit = shown.size + count)
            val next = result.picks.filter { it.candidate.id !in shown }.take(count)
            if (next.isEmpty()) {
                _state.value = PickSessionUiState.NoPick
            } else {
                shown += next.map { it.candidate.id }
                _state.value = PickSessionUiState.Result(mode, next, result.offline)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") failure: Exception) { // Any failure ends the session visibly; rethrown so the crash reporter still gets it.
            _state.value = PickSessionUiState.Failed
            throw failure
        }
    }

    private fun act(id: MediaId, outcome: PickOutcome, write: suspend (RankedPick) -> Unit) {
        val result = _state.value as? PickSessionUiState.Result ?: return
        val pick = result.picks.firstOrNull { it.candidate.id == id } ?: return
        if (id in result.outcomes) return
        viewModelScope.launchReporting {
            write(pick)
            _state.update { current ->
                if (current is PickSessionUiState.Result) current.copy(outcomes = current.outcomes + (id to outcome)) else current
            }
        }
    }
}
