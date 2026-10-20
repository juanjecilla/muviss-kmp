package com.codingpit.muviss

/**
 * The namespace half of the Sentry `release` string, per platform (#422).
 *
 * Android's Gradle plugin stamps `com.codingpit.muviss@<name>+<code>` on the R8
 * mapping, and Play's crash gate (`fastlane/sentry_gate.rb`) asks Sentry for
 * issues first seen in exactly that string. The iOS build reports the same
 * name and code, so without a distinct namespace the Play gate would hold on an
 * iOS-only crash and the App Store gate would see Android's. iOS therefore
 * reports `com.codingpit.muviss.ios@…`; every other platform keeps the
 * Android string, so nothing already in Sentry changes meaning.
 *
 * `fastlane/sentry_gate.rb` names the same two strings: change both together.
 */
internal expect val crashReleaseNamespace: String
