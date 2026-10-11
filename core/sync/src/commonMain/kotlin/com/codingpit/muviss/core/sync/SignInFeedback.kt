package com.codingpit.muviss.core.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Carries the reason a sign-in attempt failed from wherever it failed to
 * whatever screen can say so.
 *
 * It needs its own channel because the two ends are far apart: the OAuth code
 * is redeemed at app scope (`MuvissApp`, since the redirect can arrive on a
 * cold start), while the only place that can report anything is the profile
 * screen, which may not exist at that moment. A `Result` has nowhere to go
 * there, so without this the failure is simply dropped.
 *
 * That is not hypothetical. Sign-in shipped broken — the exchange was being
 * cancelled before it ran — and the bug survived three attempts at diagnosis
 * precisely because a failed sign-in looked exactly like a sign-in that had
 * not been tried: the user returns to the app, still signed out, with nothing
 * on screen. See ADR 0014.
 *
 * A typed [SignInFailure] rather than a string, so the screen that shows it
 * owns the wording (and its translation) and nothing here is copy. The one
 * free-text field, [SignInFailure.Declined.reason], is the identity provider's
 * own `error_description` from the redirect URL, never an exception's text:
 * an OAuth code exchange can fail with a Ktor/Supabase exception whose
 * message embeds the request URL, the same leak EPIC 27 swept out of every
 * other view model (see CLAUDE.md, "Network errors are `MetadataError`, never
 * exception text"; issue #90 was this constructor's own corner of it). Report
 * [SignInFailure.Generic] when there is nothing of your own to say.
 */
class SignInFeedback {
    private val state = MutableStateFlow<SignInFailure?>(null)

    /** The last failure, or null when there is nothing to report. Cleared by [consume] once shown. */
    val lastFailure: Flow<SignInFailure?> = state.asStateFlow()

    fun report(failure: SignInFailure = SignInFailure.Generic) {
        state.value = failure
    }

    /** Called once the failure has been shown, so it is not repeated on the next recomposition. */
    fun consume() {
        state.value = null
    }
}

/** Why a sign-in attempt did not finish. Structured so the UI chooses the words. */
sealed interface SignInFailure {
    /** Something went wrong and there is nothing of our own to add. */
    data object Generic : SignInFailure

    /** The browser round trip never came back within [minutes] (desktop's loopback listener). */
    data class TimedOut(val minutes: Int) : SignInFailure

    /** The redirect came back without an authorization code. */
    data object NoCode : SignInFailure

    /** The provider sent the person back with an error; [reason] is its own `error_description` or `error`, or null. */
    data class Declined(val reason: String?) : SignInFailure
}
