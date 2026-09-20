package com.codingpit.muviss.core.sync

import com.codingpit.muviss.core.sync.supabase.HTTP_FORBIDDEN
import com.codingpit.muviss.core.sync.supabase.HTTP_UNAUTHORIZED
import com.codingpit.muviss.core.sync.supabase.SupabaseHttpException

/** Why a sync cycle failed, in terms a screen can phrase without reading an exception. */
enum class SyncFailureReason {
    /** The server could not be reached: no network, DNS, a timeout. Worth retrying, and nothing to do with the account. */
    Offline,

    /** The session is dead or was refused. Retrying cannot help; a new sign-in does. */
    Unauthorised,

    /** The server answered and said no, or was down. */
    Server,

    Unknown,
    ;

    companion object {
        /**
         * Sorts a failure into a [SyncFailureReason] by looking through its cause
         * chain. Transport errors are recognised by class *name*, not type,
         * because they are different classes on every target (`UnknownHostException`
         * on the JVM, a `TypeError` from `fetch` in a browser, `DarwinHttpRequestException`
         * on iOS) and `:core:sync`'s common code can see none of them.
         */
        fun classify(failure: Throwable): SyncFailureReason {
            var current: Throwable? = failure
            var depth = 0
            while (current != null && depth < MAX_CAUSE_DEPTH) {
                classifyOne(current)?.let { return it }
                current = current.cause
                depth++
            }
            return Unknown
        }

        private fun classifyOne(failure: Throwable): SyncFailureReason? = when {
            failure is SyncSessionExpiredException -> Unauthorised

            failure is SupabaseHttpException ->
                if (failure.status == HTTP_UNAUTHORIZED || failure.status == HTTP_FORBIDDEN) Unauthorised else Server

            failure::class.simpleName in TRANSPORT_FAILURES -> Offline

            BROWSER_FETCH_FAILURES.any { failure.message?.contains(it) == true } -> Offline

            else -> null
        }

        /** Reads back what [encode] stored, or null for anything else — including text stored before this existed. */
        fun fromStored(raw: String?): SyncFailureReason? = raw?.substringBefore(':')?.let { head -> entries.firstOrNull { it.name == head } }

        /**
         * `syncState.lastError` keeps the reason as a leading token so the
         * screen can phrase it after a restart, followed by the diagnostic
         * text for whoever is reading the database. There is no column for the
         * reason because this epic owns no schema number (ADR 0021).
         */
        fun encode(reason: SyncFailureReason, detail: String?): String = if (detail.isNullOrBlank()) reason.name else "${reason.name}: ${detail.take(MAX_DETAIL_CHARS)}"

        private const val MAX_CAUSE_DEPTH = 8
        private const val MAX_DETAIL_CHARS = 300

        private val TRANSPORT_FAILURES = setOf(
            "UnknownHostException",
            "UnresolvedAddressException",
            "ConnectException",
            "NoRouteToHostException",
            "SocketException",
            "SocketTimeoutException",
            "ConnectTimeoutException",
            "HttpRequestTimeoutException",
            "DarwinHttpRequestException",
        )

        private val BROWSER_FETCH_FAILURES = listOf("Failed to fetch", "NetworkError when attempting to fetch")
    }
}

/**
 * The session cannot be used again and nothing but a new sign-in fixes it: the
 * refresh token was rejected, or there never was one. Thrown by the backend so
 * the engine can classify the failure as [SyncFailureReason.Unauthorised]
 * instead of guessing from an HTTP status that, on the refresh endpoint, also
 * means "malformed request".
 */
internal class SyncSessionExpiredException(cause: Throwable? = null) : RuntimeException("Sync session expired; sign in again${cause?.message?.let { " ($it)" }.orEmpty()}", cause)
