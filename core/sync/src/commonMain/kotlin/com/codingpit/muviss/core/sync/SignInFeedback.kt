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
 * A plain string rather than a typed error, so a caller with copy of its own
 * (a timeout, an OAuth `error` query param GoTrue hands back) can pass it
 * straight through. [message] must already be fixed, screen-safe copy —
 * **never** an exception's own text: an OAuth code exchange can fail with a
 * Ktor/Supabase exception whose message embeds the request URL, the same
 * leak EPIC 27 swept out of every other view model (see CLAUDE.md,
 * "Network errors are `MetadataError`, never exception text"; issue #90 was
 * this constructor's own corner of it). Pass null for no copy of your own —
 * [report] falls back to a fixed default.
 */
class SignInFeedback {
    private val state = MutableStateFlow<String?>(null)

    /** The last failure, or null when there is nothing to report. Cleared by [consume] once shown. */
    val lastFailure: Flow<String?> = state.asStateFlow()

    fun report(message: String?) {
        state.value = message?.takeIf { it.isNotBlank() } ?: "Couldn't finish signing in"
    }

    /** Called once the message has been shown, so it is not repeated on the next recomposition. */
    fun consume() {
        state.value = null
    }
}
