package com.codingpit.muviss.core.sync.supabase

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shapes for Supabase Auth (GoTrue)'s REST API, called directly over
 * HTTP rather than through the `supabase-kt` SDK — see ADR 0009 for why.
 * Field names/endpoints verified against GoTrue's own OpenAPI spec
 * (https://github.com/supabase/auth), not the SDK docs, which don't publish
 * the raw wire format.
 */
@Serializable
internal data class GoTrueUserDto(
    val id: String,
    val email: String? = null,
    @SerialName("is_anonymous") val isAnonymous: Boolean = false,
)

@Serializable
internal data class GoTrueSessionDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresInSeconds: Long? = null,
    val user: GoTrueUserDto,
)

/** POST `/auth/v1/token?grant_type=pkce` body — redeems the `code` from an OAuth redirect against the verifier that produced its challenge (RFC 7636). */
@Serializable
internal data class PkceExchangeRequestDto(
    @SerialName("auth_code") val authCode: String,
    @SerialName("code_verifier") val codeVerifier: String,
)

/** POST `/auth/v1/token?grant_type=refresh_token` body. The response is a full [GoTrueSessionDto] with a *rotated* refresh token — GoTrue invalidates the one sent here. */
@Serializable
internal data class RefreshTokenRequestDto(
    @SerialName("refresh_token") val refreshToken: String,
)
