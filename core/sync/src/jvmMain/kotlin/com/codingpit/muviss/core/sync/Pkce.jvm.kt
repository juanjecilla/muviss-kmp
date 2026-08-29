package com.codingpit.muviss.core.sync

import java.security.MessageDigest
import java.security.SecureRandom

// Identical to the Android actual by necessity rather than by accident: the
// default KMP hierarchy has no source set shared by android and jvm alone, and
// declaring one for eight lines would cost more than it saves.

internal actual fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)

/** `SecureRandom()` with no seeding: seeding it yourself makes it *less* random, not more. */
internal actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }
