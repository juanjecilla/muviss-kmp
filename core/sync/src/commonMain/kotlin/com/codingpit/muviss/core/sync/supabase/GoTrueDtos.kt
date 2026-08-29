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

/** POST `/auth/v1/otp` body — `create_user = true` so a first-time email can sign up via OTP instead of needing a separate signup step. */
@Serializable
internal data class OtpRequestDto(
    val email: String,
    @SerialName("create_user") val createUser: Boolean = true,
)

/** POST `/auth/v1/token?grant_type=refresh_token` body. The response is a full [GoTrueSessionDto] with a *rotated* refresh token — GoTrue invalidates the one sent here. */
@Serializable
internal data class RefreshTokenRequestDto(
    @SerialName("refresh_token") val refreshToken: String,
)

/** POST `/auth/v1/verify` body. `type = "email"` is GoTrue's OTP-code verification path (distinct from `"magiclink"`, which verifies a link token instead — see docs/SYNC.md). */
@Serializable
internal data class VerifyOtpRequestDto(
    val type: String = "email",
    val email: String,
    val token: String,
)
