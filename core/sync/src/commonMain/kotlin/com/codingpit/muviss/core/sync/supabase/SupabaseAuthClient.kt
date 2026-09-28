package com.codingpit.muviss.core.sync.supabase

import com.codingpit.muviss.core.sync.OAuthProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.Serializable

// kotlinx.serialization looks up EmptyBody's INSTANCE reflectively; a file-private
// object compiles to a package-private class, which the JVM's access check then
// rejects (IllegalAccessException, #98). internal is visible enough to fix it.
@Serializable
internal object EmptyBody

/**
 * Thin wrapper over Supabase Auth (GoTrue)'s REST endpoints, exactly the
 * ones this app needs (anonymous sign-in, OAuth authorize + code exchange,
 * refresh, sign out) — see ADR 0009 for why this is plain Ktor rather than
 * the `supabase-kt` SDK, and docs/SYNC.md for the endpoint reference this was
 * built against.
 *
 * Every call checks its status (see [ensureSuccess]): a wrong code, a rate
 * limit or an unconfigured provider all answer 4xx with a JSON body, which
 * `body()` alone would either swallow or report as an unrelated
 * deserialization failure.
 */
internal class SupabaseAuthClient(
    private val client: HttpClient,
    private val baseUrl: String,
    private val anonKey: String,
) {
    /** `POST /auth/v1/signup` with an empty body — GoTrue's documented anonymous sign-in shape when "Enable anonymous sign-ins" is on for the project. */
    suspend fun signInAnonymously(): GoTrueSessionDto = client.post("$baseUrl/auth/v1/signup") {
        applyAuthHeaders()
        setBody(EmptyBody)
    }.also { it.ensureSuccess("anonymous sign-in") }.body()

    /**
     * The URL to open in a browser to start OAuth — `GET /auth/v1/authorize`.
     *
     * Built rather than requested: this endpoint answers with a 302 to the
     * provider, and following it here would authenticate the HTTP client
     * instead of the user. The browser has to make the request.
     *
     * [codeChallenge] is the SHA-256 of a verifier this client keeps (see
     * [Pkce]); `s256` is the only method worth sending, since the `plain`
     * alternative transmits the verifier itself and protects nothing.
     */
    fun authorizeUrl(provider: OAuthProvider, redirectUri: String, codeChallenge: String): String = buildString {
        append("$baseUrl/auth/v1/authorize")
        append("?provider=${provider.wireName.encodeURLParameter()}")
        append("&redirect_to=${redirectUri.encodeURLParameter()}")
        append("&code_challenge=${codeChallenge.encodeURLParameter()}")
        append("&code_challenge_method=s256")
    }

    /**
     * `POST /auth/v1/token?grant_type=pkce` — redeems the `code` from the
     * redirect, proving with [codeVerifier] that it was issued to this
     * device's attempt rather than intercepted from it.
     */
    suspend fun exchangeOAuthCode(authCode: String, codeVerifier: String): GoTrueSessionDto = client.post("$baseUrl/auth/v1/token") {
        applyAuthHeaders()
        parameter("grant_type", "pkce")
        setBody(PkceExchangeRequestDto(authCode = authCode, codeVerifier = codeVerifier))
    }.also { it.ensureSuccess("OAuth code exchange") }.body()

    /**
     * `POST /auth/v1/token?grant_type=refresh_token`. Supabase access tokens
     * expire in about an hour; without this a session signed in on Monday
     * stops syncing on Monday and never recovers on its own. GoTrue rotates
     * the refresh token on every use, so the returned session — not the one
     * that was passed in — is the one to persist.
     */
    suspend fun refreshSession(refreshToken: String): GoTrueSessionDto = client.post("$baseUrl/auth/v1/token") {
        applyAuthHeaders()
        parameter("grant_type", "refresh_token")
        setBody(RefreshTokenRequestDto(refreshToken = refreshToken))
    }.also { it.ensureSuccess("session refresh") }.body()

    /** `POST /auth/v1/logout`, scoped to just this session's own token (not `scope=global`, which would sign the account out everywhere). */
    suspend fun signOut(accessToken: String) {
        client.post("$baseUrl/auth/v1/logout") {
            applyAuthHeaders()
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }.ensureSuccess("sign out")
    }

    private fun HttpRequestBuilder.applyAuthHeaders() {
        header("apikey", anonKey)
        contentType(ContentType.Application.Json)
    }
}
