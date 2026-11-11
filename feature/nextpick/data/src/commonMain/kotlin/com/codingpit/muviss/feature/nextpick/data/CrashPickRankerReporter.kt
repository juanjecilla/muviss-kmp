package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.core.common.crash.CrashReporter
import com.codingpit.muviss.feature.nextpick.domain.PickRankerReporter

/**
 * Sends a ranker that did not answer to the crash reporter (#605). Separate classes, so Sentry files a
 * timeout, and an answer with nothing usable (#674), as events of their own rather than as failures.
 *
 * Neither carries a cause: Sentry walks the cause chain, and a ranker's own exception is the one place a
 * message could come from. The message is the exception's class name, which is all a developer needs.
 */
internal class CrashPickRankerReporter(
    private val sink: (Throwable) -> Unit = CrashReporter::recordException,
) : PickRankerReporter {

    override fun failed(exceptionClass: String) = sink(PickRankerFailed(exceptionClass))

    override fun timedOut() = sink(PickRankerTimedOut())

    override fun unusable(returned: Int) = sink(PickRankerUnusable(returned))
}

/** A ranker threw. [exceptionClass] is the thrown exception's class name, never its message. */
internal class PickRankerFailed(exceptionClass: String) : Exception(exceptionClass)

/** A ranker ran past the engine's timeout and the rules answered instead. */
internal class PickRankerTimedOut : Exception("PickRanker timed out")

/** A ranker answered, but none of its [returned] picks could be shown. The count is all that is sent. */
internal class PickRankerUnusable(returned: Int) : Exception("PickRanker returned $returned unusable picks")
