package com.codingpit.muviss.core.common.crash

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlin.coroutines.CoroutineContext

/**
 * The one [CoroutineExceptionHandler] every `viewModelScope` uses.
 *
 * It reports the failure through [CrashReporter.recordException] and does not
 * rethrow. Without it an exception escaping a `launch` in a view model goes to
 * the platform's uncaught-exception handler and takes the app down on the way
 * to Sentry — or, where the failure was already handled somewhere, is lost. With
 * it, one failed action costs one report and the screen keeps working, which is
 * what a `SupervisorJob` scope is for.
 *
 * [CancellationException] is not a failure and is never reported.
 */
val CrashReportingExceptionHandler: CoroutineExceptionHandler = reportingExceptionHandler(CrashReporter::recordException)

/** [CrashReportingExceptionHandler] with the reporter injected, so a test can see what it is handed. */
internal fun reportingExceptionHandler(report: (Throwable) -> Unit): CoroutineExceptionHandler = CoroutineExceptionHandler { _: CoroutineContext, throwable: Throwable ->
    if (throwable !is CancellationException) runCatching { report(throwable) }
}

/** `launch` with [CrashReportingExceptionHandler] attached; the spelling every view model uses. */
fun CoroutineScope.launchReporting(block: suspend CoroutineScope.() -> Unit): Job = launch(CrashReportingExceptionHandler, block = block)

/** `Flow.launchIn` with [CrashReportingExceptionHandler] on the scope, for a view model's long-lived collectors. */
fun <T> Flow<T>.launchInReporting(scope: CoroutineScope): Job = launchIn(scope + CrashReportingExceptionHandler)

/**
 * For a caller that catches on purpose — a `runCatching` whose failure the UI
 * shows — and would otherwise leave the operator blind to it. Returns the
 * receiver so it chains, and rethrows cancellation, which `runCatching` alone
 * would swallow.
 */
fun <T> Result<T>.reportFailure(): Result<T> {
    exceptionOrNull()?.let { failure ->
        if (failure is CancellationException) throw failure
        CrashReporter.recordException(failure)
    }
    return this
}

/**
 * [reportFailure], narrowed to the failures worth a report (#108).
 *
 * The policy: **report by default, with an explicit opt-out allowlist** —
 * [isExpected] names the known-noisy, already-surfaced-in-UI cases (a bad
 * network, a malformed file the person picked), not the other way around.
 * Silence is the thing that has to be earned per call site; a new failure mode
 * nobody thought to exclude is reported until proven routine, never dropped by
 * default. That is what keeps a genuine bug from getting buried under an
 * unfiltered stream of "the user was offline" — the failure this exists to
 * avoid — without also going silent on everything a `runCatching` was only
 * ever meant to turn into UI copy.
 *
 * Rethrows cancellation exactly like [reportFailure]; a `CancellationException`
 * is never "expected" or "unexpected", it is not a failure at all.
 */
fun <T> Result<T>.reportUnexpectedFailure(isExpected: (Throwable) -> Boolean): Result<T> = reportUnexpectedFailure(isExpected, CrashReporter::recordException)

/** [reportUnexpectedFailure] with the reporter injected, so a test can see what it is/isn't handed. */
internal fun <T> Result<T>.reportUnexpectedFailure(isExpected: (Throwable) -> Boolean, report: (Throwable) -> Unit): Result<T> {
    exceptionOrNull()?.let { failure ->
        if (failure is CancellationException) throw failure
        if (!isExpected(failure)) report(failure)
    }
    return this
}
