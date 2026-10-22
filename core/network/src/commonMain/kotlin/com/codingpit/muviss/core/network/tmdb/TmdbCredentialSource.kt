package com.codingpit.muviss.core.network.tmdb

import com.codingpit.muviss.core.common.remoteconfig.RemoteConfig

/** Which level of the TMDB Credential Chain supplied a credential. */
internal enum class TmdbCredentialOrigin { User, Remote, BuiltIn }

internal class ResolvedTmdbCredentials(val credentials: TmdbCredentials, val origin: TmdbCredentialOrigin)

/**
 * Where [TmdbProvider] gets its credential, asked **once per request** so a
 * change (a key saved in Settings, a token rotated remotely) applies without a
 * restart.
 *
 * The rule (ADR 0027) is that the credential used is the first one *present*:
 * the user's Own TMDB Key, then the token Remote Config serves, then the one
 * built into the binary. A level is skipped only when it is absent, never
 * because TMDB rejected it: quietly falling back would hide that a credential
 * the user chose, or one we rotated to, stopped working.
 */
internal fun interface TmdbCredentialSource {
    suspend fun current(): ResolvedTmdbCredentials
}

/** A fixed credential — what tests and a provider with nothing remote use. */
internal class FixedTmdbCredentialSource(
    private val credentials: TmdbCredentials,
    private val origin: TmdbCredentialOrigin = TmdbCredentialOrigin.BuiltIn,
) : TmdbCredentialSource {
    override suspend fun current(): ResolvedTmdbCredentials = ResolvedTmdbCredentials(credentials, origin)
}

/**
 * The chain: [ownKey], then the remote fallback token, then [builtIn].
 *
 * Awaits the *stored* remote config (a local read, never the network) before
 * choosing. Reading the flow's compiled default instead would ignore a rotated
 * token exactly when it matters: on the first request after a cold start, with
 * the built-in credential revoked. A served token **replaces** the built-in
 * credential; it is never added to it, so a revoked v3 key does not ride along.
 */
internal class ChainedTmdbCredentialSource(
    private val ownKey: OwnTmdbKey?,
    private val remoteConfig: RemoteConfig,
    private val builtIn: TmdbCredentials,
) : TmdbCredentialSource {
    override suspend fun current(): ResolvedTmdbCredentials {
        ownKey?.credentials()?.let { return ResolvedTmdbCredentials(it, TmdbCredentialOrigin.User) }
        val token = remoteConfig.awaitStored().tmdbFallbackReadToken
        if (!token.isNullOrBlank()) {
            return ResolvedTmdbCredentials(TmdbCredentials(readToken = token), TmdbCredentialOrigin.Remote)
        }
        return ResolvedTmdbCredentials(builtIn, TmdbCredentialOrigin.BuiltIn)
    }
}
