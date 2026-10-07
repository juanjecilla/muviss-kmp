package com.codingpit.muviss.core.network.tmdb

/**
 * Where a swallowed TMDB failure leaves a trace (#177).
 *
 * [tmdbCall] replaces every failure with a fixed-text, cause-less
 * `MetadataError`, because the original message can carry the request URL and
 * with it the v3 `api_key` (EPIC 27). That is right for the UI and for crash
 * reports, but it left a debug build with nothing at all to read when a call
 * failed — the Search "offline" bug that was really a cache miss could only be
 * found by breaking in a debugger.
 *
 * A line is the endpoint path and the failure's **class-name chain**, e.g.
 * `TMDB search/multi failed: InvalidCacheStateException -> Unknown`. Class
 * names cannot carry a URL or a key; messages are never included. The default
 * is [None]; a host binds a real one only in debug builds (Android: a logcat
 * trace when the app is debuggable, see `MuvissApplication`).
 */
fun interface TmdbFailureTrace {
    fun record(line: String)

    companion object {
        val None: TmdbFailureTrace = TmdbFailureTrace { }
    }
}

/** `TMDB <path> failed: <cause chain> -> <mapped error>`, built from class names only. */
internal fun tmdbFailureLine(path: String, failure: Throwable, mapped: Throwable): String {
    val chain = generateSequence(failure) { it.cause }.take(MAX_CHAIN).map { it.className() }.toList()
    val steps = if (failure === mapped) chain else chain + mapped.className()
    return "TMDB ${path.substringBefore('?')} failed: ${steps.joinToString(" -> ")}"
}

private fun Throwable.className(): String = this::class.simpleName ?: "Throwable"

private const val MAX_CHAIN = 5
