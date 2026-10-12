package com.codingpit.muviss.core.network.tmdb

import com.codingpit.muviss.core.common.remoteconfig.RemoteConfig

/**
 * Where [TmdbProvider] gets its credential, asked **once per request** so a
 * change applies without a restart (EPIC 45; EPIC 46 adds the user's own key
 * in front).
 *
 * The rule (ADR 0027) is that the credential used is the first one *present*.
 * A level is skipped only when it is absent, never because TMDB rejected it:
 * quietly falling back would hide that a served or chosen credential stopped
 * working.
 */
internal fun interface TmdbCredentialSource {
    suspend fun current(): TmdbCredentials
}

/** A fixed credential — what tests and a provider with nothing remote use. */
internal class FixedTmdbCredentialSource(private val credentials: TmdbCredentials) : TmdbCredentialSource {
    override suspend fun current(): TmdbCredentials = credentials
}

/**
 * Remote fallback token, then the credential built into the binary.
 *
 * Awaits the *stored* remote config (a local read, never the network) before
 * choosing. Reading the flow's compiled default instead would ignore a rotated
 * token exactly when it matters: on the first request after a cold start, with
 * the built-in credential revoked.
 */
internal class RemoteFirstTmdbCredentialSource(
    private val remoteConfig: RemoteConfig,
    private val builtIn: TmdbCredentials,
) : TmdbCredentialSource {
    override suspend fun current(): TmdbCredentials {
        val token = remoteConfig.awaitStored().tmdbFallbackReadToken
        return if (token.isNullOrBlank()) builtIn else TmdbCredentials(readToken = token)
    }
}
