@file:OptIn(ExperimentalEncodingApi::class, ExperimentalTime::class)

package com.codingpit.muviss.core.sync.supabase

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * When the `sync_until` claim in a Supabase access token says this account's
 * sync grant ends, as epoch milliseconds, or null when the token carries no
 * such claim — which is how the token hook says "not entitled" (ADR 0019,
 * `supabase/migrations/20261010000000_entitlement.sql`).
 *
 * The payload is read, **not verified**. That is enough for what the client
 * uses it for — deciding whether to ask at all, and whether a refusal is the
 * paywall — because the server verifies the same token on every request and
 * its policies compare the same claim. A forged claim buys a patched client
 * nothing but a 403.
 *
 * Anything malformed (not three segments, not base64url, not JSON, a claim
 * that is not an ISO-8601 instant) reads as no claim: fail closed.
 */
internal fun syncUntilClaimEpochMs(accessToken: String): Long? {
    val payload = accessToken.split('.').takeIf { it.size == JWT_SEGMENTS }?.get(1) ?: return null
    val claims = runCatching { Json.parseToJsonElement(jwtSegment.decode(payload).decodeToString()) }.getOrNull() as? JsonObject ?: return null
    val raw = (claims[SYNC_UNTIL_CLAIM] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
    return parseInstantEpochMs(raw)
}

/** An ISO-8601 instant — `2026-11-09T00:16:15Z` from the hook, `2026-11-09T00:16:15.289+00:00` from PostgREST — as epoch milliseconds, or null. */
internal fun parseInstantEpochMs(raw: String): Long? = runCatching { Instant.parse(raw).toEpochMilliseconds() }.getOrNull()

/** JWT segments are base64url with the padding stripped (RFC 7515 §2); tolerate it being present anyway. */
private val jwtSegment = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

private const val JWT_SEGMENTS = 3
private const val SYNC_UNTIL_CLAIM = "sync_until"
