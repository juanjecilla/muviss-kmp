package com.codingpit.muviss.core.sync

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

// Both primitives come from the platform, no dependency: CommonCrypto for the
// digest, Security for the CSPRNG.
//
// These were deliberately left unimplemented while the comment here claimed
// iOS sign-in needed `ASWebAuthenticationSession`. It does not:
// `ProfileScreen` opens the authorize URL through Compose's `LocalUriHandler`
// — common code on every target — which on iOS is `UIApplication.openURL`, so
// the round trip is Safari plus the `muviss://auth-callback` scheme
// registered in `app/iosApp/iosApp/Info.plist`. ASWebAuthenticationSession
// would be a UX improvement (no app-switch, ephemeral session), not a
// requirement.

@OptIn(ExperimentalForeignApi::class)
internal actual fun sha256(input: ByteArray): ByteArray {
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
    digest.usePinned { out ->
        if (input.isEmpty()) {
            // addressOf(0) on an empty array throws; CC_SHA256 takes a null
            // pointer with length 0 and still writes the digest of "".
            CC_SHA256(null, 0.convert(), out.addressOf(0).reinterpret())
        } else {
            input.usePinned { data ->
                CC_SHA256(data.addressOf(0), input.size.convert(), out.addressOf(0).reinterpret())
            }
        }
    }
    return digest
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(size: Int): ByteArray {
    require(size > 0) { "size must be positive, was $size" }
    val bytes = ByteArray(size)
    val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, size.convert(), it.addressOf(0)) }
    // Failing loudly rather than falling back: a silently weak verifier
    // defeats PKCE entirely (see the expect declaration's doc comment).
    check(status == errSecSuccess) { "SecRandomCopyBytes failed with OSStatus $status" }
    return bytes
}
