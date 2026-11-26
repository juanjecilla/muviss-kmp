package com.codingpit.muviss.appfunctions

import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionElementNotFoundException
import androidx.appfunctions.AppFunctionException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionLimitExceededException
import com.codingpit.muviss.core.common.crash.CrashReporter
import com.codingpit.muviss.models.MetadataError
import kotlinx.coroutines.CancellationException

/** Which AppFunction exception a failure maps to. Kept apart from the exception types so the mapping is unit-testable. */
internal enum class FailureKind { INVALID_ARGUMENT, NOT_FOUND, LIMIT_EXCEEDED, APP_UNKNOWN }

/** A failure with its kind and fixed, agent-readable text. The text never comes from an exception message (CLAUDE.md). */
internal data class AppFunctionFailure(val kind: FailureKind, val text: String)

internal const val GENERIC_FAILURE_TEXT = "Muviss could not complete this request. Try again later."

/** The fixed failure for every [MetadataError]. Ktor messages can carry the request URL and a TMDB key, so none is kept. */
internal fun MetadataError.appFunctionFailure(): AppFunctionFailure = when (this) {
    is MetadataError.RateLimited -> AppFunctionFailure(
        FailureKind.LIMIT_EXCEEDED,
        "The movie database is rate limiting requests. Try again in a minute.",
    )

    is MetadataError.Unauthorized -> AppFunctionFailure(
        FailureKind.APP_UNKNOWN,
        "Muviss cannot reach the movie database with its current credential. Updating Muviss may fix this.",
    )

    is MetadataError.OwnKeyRejected -> AppFunctionFailure(
        FailureKind.APP_UNKNOWN,
        "The user's own TMDB key was rejected. Ask the user to check it under TMDB data source in Muviss Settings.",
    )

    is MetadataError.NotFound -> AppFunctionFailure(
        FailureKind.NOT_FOUND,
        "The movie database has no title for that mediaId.",
    )

    is MetadataError.Offline -> AppFunctionFailure(
        FailureKind.APP_UNKNOWN,
        "The device is offline, so the movie database cannot be reached. Try again when it is online.",
    )

    is MetadataError.Unknown -> AppFunctionFailure(
        FailureKind.APP_UNKNOWN,
        "The movie database failed to answer. Try again later.",
    )
}

internal fun AppFunctionFailure.toException(): AppFunctionException = when (kind) {
    FailureKind.INVALID_ARGUMENT -> AppFunctionInvalidArgumentException(text)
    FailureKind.NOT_FOUND -> AppFunctionElementNotFoundException(text)
    FailureKind.LIMIT_EXCEEDED -> AppFunctionLimitExceededException(text)
    FailureKind.APP_UNKNOWN -> AppFunctionAppUnknownException(text)
}

/**
 * An argument the agent sent that Muviss cannot use. A plain exception on purpose: constructing an
 * [AppFunctionInvalidArgumentException] needs Android's Bundle, which JVM unit tests do not have. The guard turns it
 * into the platform type with the same fixed text.
 */
internal class InvalidAgentArgument(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

internal fun invalidArgument(text: String, cause: Throwable? = null): InvalidAgentArgument = InvalidAgentArgument(text, cause)

/** A title, episode or other item the agent named that Muviss does not have. Mapped to ElementNotFound by the guard. */
internal class MissingAgentItem(message: String) : RuntimeException(message)

/**
 * Runs one AppFunction body, turning every failure into an [AppFunctionException] with fixed text.
 *
 * The platform would otherwise hand the agent the raw exception message (the docs call this out), so anything
 * not already an AppFunction exception is recorded to crash reporting and replaced with [GENERIC_FAILURE_TEXT].
 * Cancellation is rethrown untouched.
 */
@Suppress("SwallowedException", "TooGenericExceptionCaught") // The MetadataError and the parser error are dropped on purpose (no cause, fixed text).
internal suspend inline fun <T> guardAppFunction(crossinline block: suspend () -> T): T = try {
    block()
} catch (e: AppFunctionException) {
    throw e
} catch (e: InvalidAgentArgument) {
    throw AppFunctionInvalidArgumentException(e.message.orEmpty())
} catch (e: MissingAgentItem) {
    throw AppFunctionElementNotFoundException(e.message.orEmpty())
} catch (e: MetadataError) {
    throw e.appFunctionFailure().toException()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    CrashReporter.recordException(e)
    throw AppFunctionAppUnknownException(GENERIC_FAILURE_TEXT)
}
