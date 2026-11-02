package com.codingpit.muviss.core.network.tmdb

/**
 * Tells a TMDB v4 read token from a v3 key by shape, so the user pastes
 * whichever TMDB's dashboard showed them and the app sends it the right way
 * (bearer header vs `api_key` query, see [authorize]).
 *
 * Shape only, never validity: whether TMDB accepts it is asked of TMDB
 * ([OwnTmdbKey.save]). A string that fits neither shape is not sent anywhere.
 */
internal object TmdbKeyShape {
    // A v4 read token is a JWT: three base64url segments, header always starting `eyJ`.
    private val v4Token = Regex("^eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")

    // A v3 key is 32 hex characters.
    private val v3Key = Regex("^[0-9a-fA-F]{32}$")

    /** The credential [raw] describes, or null if it is neither shape. Surrounding whitespace is ignored. */
    fun detect(raw: String): TmdbCredentials? {
        val value = raw.trim()
        return when {
            v4Token.matches(value) -> TmdbCredentials(readToken = value)
            v3Key.matches(value) -> TmdbCredentials(apiKey = value)
            else -> null
        }
    }
}
