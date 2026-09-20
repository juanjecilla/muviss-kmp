package com.codingpit.muviss.core.common.crash

/**
 * Strips credentials out of text on its way to Sentry.
 *
 * The TMDB key travels as `?api_key=` on every request and the Supabase anon key
 * as an `apikey` header, and HTTP client errors put the request URL in their
 * message — so without this, a failed search would send the key baked into the
 * app to a third party inside the crash report. `docs/PRIVACY.md` promises the
 * reverse. It pairs with EPIC 27 (network hardening), which stops such messages
 * being built in the first place; this is the net under it.
 *
 * Deliberately over-eager: `token: expired` becomes `token: [redacted]`. A report
 * that loses a word is fine; one that leaks a key is not.
 */
object CrashScrubber {
    const val REDACTED: String = "[redacted]"

    // `name = value` / `name: value` / `"name":"value"` / `?name=value`. Group 1 is
    // the key, group 2 the separator (with any quotes), group 3 the secret.
    private val keyed = Regex(
        """(api[_-]?key|access[_-]?token|refresh[_-]?token|id[_-]?token|client[_-]?secret|code[_-]?verifier|secret|password|passwd|token)(["']?\s*[=:]\s*["']?)([^&\s"',;#)]+)""",
        RegexOption.IGNORE_CASE,
    )

    private val bearer = Regex("""\bBearer\s+[A-Za-z0-9._~+/=-]+""", RegexOption.IGNORE_CASE)

    fun scrub(text: String): String = bearer
        .replace(keyed.replace(text) { m -> m.groupValues[1] + m.groupValues[2] + REDACTED }) { "Bearer $REDACTED" }

    /** For the SDK's nullable fields. */
    fun scrubOrNull(text: String?): String? = text?.let(::scrub)
}
