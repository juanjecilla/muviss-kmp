package com.codingpit.muviss.core.sync.supabase

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

@Serializable
private object EmptyBody

/**
 * Thin wrapper over Supabase Auth (GoTrue)'s REST endpoints, exactly the
 * four this app needs (anonymous sign-in, request OTP, verify OTP, sign
 * out) — see ADR 0009 for why this is plain Ktor rather than the
 * `supabase-kt` SDK, and docs/SYNC.md for the endpoint reference this was
 * built against.
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
    }.body()

    /** `POST /auth/v1/otp`. No session yet — the user must supply the code they receive via [verifyEmailOtp]. */
    suspend fun requestEmailOtp(email: String) {
        client.post("$baseUrl/auth/v1/otp") {
            applyAuthHeaders()
            setBody(OtpRequestDto(email = email))
        }
    }

    /** `POST /auth/v1/verify` with `type = "email"` — completes the OTP challenge and returns a real session. */
    suspend fun verifyEmailOtp(email: String, token: String): GoTrueSessionDto = client.post("$baseUrl/auth/v1/verify") {
        applyAuthHeaders()
        setBody(VerifyOtpRequestDto(email = email, token = token))
    }.body()

    /** `POST /auth/v1/logout`, scoped to just this session's own token (not `scope=global`, which would sign the account out everywhere). */
    suspend fun signOut(accessToken: String) {
        client.post("$baseUrl/auth/v1/logout") {
            applyAuthHeaders()
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
    }

    private fun HttpRequestBuilder.applyAuthHeaders() {
        header("apikey", anonKey)
        contentType(ContentType.Application.Json)
    }
}
