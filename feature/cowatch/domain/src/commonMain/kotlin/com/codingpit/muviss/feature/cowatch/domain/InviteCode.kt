package com.codingpit.muviss.feature.cowatch.domain

/**
 * The invite code: this account's user id and a fresh nonce, in the clear.
 *
 * It is self-describing because RLS leaves no alternative. Accepting an invite
 * means writing a row addressed to the inviter, so the accepting device has to
 * learn the inviter's user id — and it cannot look one up. Every policy in this
 * schema is a flat `auth.uid()` comparison, and a code-for-id exchange would
 * need a `security definer` RPC, which this project has never had and which
 * ADR 0022 declined to introduce for this.
 *
 * So the code carries what a lookup would have returned. Two consequences, both
 * accepted deliberately:
 *
 * - **A user id is in every code you send.** It is a uuid, so it is not
 *   guessable, but anyone holding one could write a row addressed at you.
 * - **The nonce is what makes that harmless.** An unsolicited row is not an
 *   invitation: the app only surfaces an acceptance whose nonce matches a code
 *   this account actually issued, and both sides must still confirm.
 *
 * Deliberately plain text rather than base64: it is short enough to paste,
 * carries nothing secret that encoding would protect, and a reader can see
 * exactly what they are sending someone. Obfuscation here would only make it
 * harder to tell.
 */
data class InviteCode(val userId: String, val nonce: String) {

    /** The pasteable form, e.g. `3f2a…-9c1b.7d41e0a2`. */
    fun encode(): String = "$userId$SEPARATOR$nonce"

    /** The deep-link form, for a code shared as a tappable link. */
    fun toDeepLink(): String = "$DEEP_LINK_PREFIX?u=$userId&n=$nonce"

    companion object {
        private const val SEPARATOR = '.'
        const val DEEP_LINK_PREFIX: String = "muviss://cowatch"

        /** A nonce this many hex characters long. 16 hex chars is 64 bits, which is plenty to make a collision between two live invites a non-event. */
        const val NONCE_HEX_LENGTH: Int = 16

        /**
         * Parses either form, or fails.
         *
         * Validation is deliberately strict about shape rather than trusting
         * the string: a code arrives from outside, sometimes from a link a
         * person tapped, and the id in it becomes the address of a row this
         * device writes.
         */
        fun decode(raw: String): Result<InviteCode> {
            val trimmed = raw.trim()
            val body = when {
                trimmed.startsWith("$DEEP_LINK_PREFIX?") -> return decodeDeepLink(trimmed)
                else -> trimmed
            }
            val separator = body.lastIndexOf(SEPARATOR)
            if (separator <= 0 || separator == body.lastIndex) {
                return Result.failure(IllegalArgumentException("not an invite code"))
            }
            return validated(body.substring(0, separator), body.substring(separator + 1))
        }

        private fun decodeDeepLink(link: String): Result<InviteCode> {
            val query = link.substringAfter('?', "")
            val parameters = query.split('&')
                .mapNotNull { part ->
                    val index = part.indexOf('=')
                    if (index <= 0) null else part.substring(0, index) to part.substring(index + 1)
                }
                .toMap()
            val userId = parameters["u"] ?: return Result.failure(IllegalArgumentException("invite link has no user id"))
            val nonce = parameters["n"] ?: return Result.failure(IllegalArgumentException("invite link has no nonce"))
            return validated(userId, nonce)
        }

        private fun validated(userId: String, nonce: String): Result<InviteCode> = when {
            !isUuid(userId) -> Result.failure(IllegalArgumentException("invite code does not carry a user id"))

            nonce.length != NONCE_HEX_LENGTH || !nonce.all { it.isHex() } ->
                Result.failure(IllegalArgumentException("invite code does not carry a well-formed nonce"))

            else -> Result.success(InviteCode(userId.lowercase(), nonce.lowercase()))
        }

        /** Supabase user ids are uuids. Checked by shape only — the server is what actually decides an id exists. */
        private fun isUuid(value: String): Boolean {
            if (value.length != 36) return false
            return value.withIndex().all { (index, character) ->
                if (index in UUID_DASHES) character == '-' else character.isHex()
            }
        }

        private val UUID_DASHES = setOf(8, 13, 18, 23)

        private fun Char.isHex(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}
