package com.codingpit.muviss.core.secrets

import eu.anifantakis.lib.ksafe.KSafe

/**
 * The platform's KSafe. Android cannot make one without a `Context`, so its
 * actual refuses and the host binds the store itself with [androidSecretStore]
 * — the same shape as `DatabaseDriverFactory(context)` and the other
 * Android-only bindings `MuvissApplication` overrides.
 */
internal expect fun createPlatformKSafe(): KSafe

/** The one KSafe store Muviss keeps its secrets in; `clearAll` on it wipes exactly these. */
internal const val SECRETS_FILE = "secrets"

internal const val APP_NAMESPACE = "com.codingpit.muviss"
