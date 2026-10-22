package com.codingpit.muviss.feature.settings.domain

import com.codingpit.muviss.core.common.remoteconfig.FeatureRequestProvider

/**
 * What a Feature Request carries to the form as hidden fields (ADR 0035). This is an
 * allow-list, deliberately a closed type: enough to triage a request ("only on iOS 17",
 * "only in Spanish") and nothing that identifies a person or a device — no install or
 * account id, nothing from the library. Add a field here only after amending the privacy
 * policy, and add it to `scripts/feedback/tally-form.json` too, or the form drops it.
 */
data class FeatureRequestContext(
    val version: String,
    val platform: String,
    val os: String,
    val locale: String,
) {
    internal fun fields(): List<Pair<String, String>> = listOf(
        "version" to version,
        "platform" to platform,
        "os" to os,
        "locale" to locale,
    ).filter { it.second.isNotBlank() }
}

/** Builds the URL the Feature Request row opens. Pure, so both providers are pinned by plain tests. */
object FeatureRequestLink {
    /**
     * Returns null — hide the row — when [provider] is [FeatureRequestProvider.Off] or
     * [baseUrl] is not an absolute `https://` URL (null, blank, a ConfigCat placeholder such
     * as `none`, a bare `tally.so/r/abc`, or `http://`, which would send the context fields
     * in the clear). Tally reads hidden fields from the query string,
     * Typeform from the URL fragment, which is why the provider matters here.
     */
    fun build(provider: FeatureRequestProvider, baseUrl: String?, context: FeatureRequestContext): String? {
        val base = baseUrl?.trim().orEmpty()
        if (provider == FeatureRequestProvider.Off || !isAbsoluteHttps(base)) return null
        val fields = context.fields().joinToString("&") { (k, v) -> "$k=${encode(v)}" }
        if (fields.isEmpty()) return base
        return when (provider) {
            FeatureRequestProvider.Tally -> {
                val (withoutFragment, fragment) = splitFragment(base)
                val separator = if ('?' in withoutFragment) "&" else "?"
                withoutFragment + separator + fields + fragment
            }

            FeatureRequestProvider.Typeform -> {
                val (withoutFragment, fragment) = splitFragment(base)
                val existing = fragment.removePrefix("#")
                withoutFragment + "#" + if (existing.isEmpty()) fields else "$existing&$fields"
            }

            FeatureRequestProvider.Off -> null
        }
    }

    private fun isAbsoluteHttps(url: String): Boolean {
        val scheme = "https://"
        if (!url.startsWith(scheme, ignoreCase = true)) return false
        val host = url.substring(scheme.length).takeWhile { it !in "/?#" }
        return host.isNotEmpty() && host.none { it.isWhitespace() }
    }

    private fun splitFragment(url: String): Pair<String, String> {
        val i = url.indexOf('#')
        return if (i < 0) url to "" else url.substring(0, i) to url.substring(i)
    }

    /** Percent-encodes everything outside the RFC 3986 unreserved set, so `+` and spaces survive a form's query parsing. */
    private fun encode(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val c = byte.toInt() and 0xFF
            if (isUnreserved(c.toChar())) {
                append(c.toChar())
            } else {
                append('%')
                append(HEX[c shr 4])
                append(HEX[c and 0xF])
            }
        }
    }

    private fun isUnreserved(ch: Char): Boolean = ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-._~"

    private const val HEX = "0123456789ABCDEF"
}
