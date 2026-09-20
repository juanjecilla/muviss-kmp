package com.codingpit.muviss.core.network

import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.Logger

private const val REDACTED = "<redacted>"

private val apiKeyParameter = Regex("""(api_key=)[^&\s"']+""", RegexOption.IGNORE_CASE)
private val bearerToken = Regex("""(Bearer\s+)[^\s"']+""", RegexOption.IGNORE_CASE)

/**
 * Removes anything shaped like a TMDB credential from [text]: the v3 key in a
 * query string (`api_key=...`) and a v4 token after `Bearer`.
 *
 * Nothing in this module lets a credential reach a message on purpose (the
 * error type carries no cause, the token rides in a header) — this is the
 * belt for the one place that prints raw request lines, the client's
 * [Logger], and for callers composing their own text.
 */
internal fun redactCredentials(text: String): String = text
    .replace(apiKeyParameter) { it.groupValues[1] + REDACTED }
    .replace(bearerToken) { it.groupValues[1] + REDACTED }

/** A [Logger] that scrubs credentials from every line before delegating to [delegate]. */
internal class RedactingLogger(private val delegate: Logger = Logger.DEFAULT) : Logger {
    override fun log(message: String) = delegate.log(redactCredentials(message))
}
