package com.codingpit.muviss.models

/**
 * Why a metadata call (search, detail, refresh...) failed, in terms a screen
 * can act on. Every failure that leaves `:core:network`'s TMDB provider is one
 * of these, so a view model never has to inspect a Ktor, serialization or
 * platform exception.
 *
 * Two properties are deliberate and must survive edits:
 *
 * - **The message is fixed text and there is no `cause`.** Ktor's timeout and
 *   I/O exceptions embed the full request URL, which for the v3 credential
 *   fallback contains the API key. A cause would travel with the exception to
 *   whatever renders or reports it (Sentry walks the cause chain), so it is not
 *   kept.
 * - **The set is closed.** EPIC 30 maps these to UI copy; adding a case is a
 *   change every `when` over it has to make.
 *
 * [userMessage] is the default English copy, used until screens own their
 * strings.
 */
sealed class MetadataError(message: String, val userMessage: String) : Exception(message) {

    /** The source is throttling us (HTTP 429). [retryAfterSeconds] is its `Retry-After`, when it sent one. */
    class RateLimited(val retryAfterSeconds: Int? = null) : MetadataError("Metadata source rate limited the request", "Too many requests right now. Try again in a moment.")

    /**
     * The source rejected our credential (HTTP 401): a configuration fault, not something the user can fix.
     *
     * Its copy must not read as a connection problem. It once said "Couldn't reach the movie database", and
     * a release built with no credential at all (#197) looked offline until someone read the status code;
     * nor does it promise that later will help (#258). A newer build is the only thing that can.
     */
    class Unauthorized : MetadataError("Metadata source rejected the credential", "Muviss can't access the movie database. Updating the app may fix this.")

    /** The title, season or episode does not exist at the source (HTTP 404). */
    class NotFound : MetadataError("Metadata source has no such item", "We couldn't find that title.")

    /** No usable connection: no route, DNS failure, connection reset or a timeout. */
    class Offline : MetadataError("No connection to the metadata source", "You appear to be offline. Check your connection and try again.")

    /** Anything else: a 5xx that survived retries, an unreadable or empty body. */
    class Unknown : MetadataError("Metadata request failed", "Something went wrong. Please try again.")
}

/**
 * The text a screen may show for this failure. A [MetadataError] maps to its
 * own copy; anything else maps to [fallback] and its `message` is discarded,
 * because arbitrary exception text can carry a URL, a key or a stack of SQL.
 */
fun Throwable.toUserMessage(fallback: String): String = (this as? MetadataError)?.userMessage ?: fallback
