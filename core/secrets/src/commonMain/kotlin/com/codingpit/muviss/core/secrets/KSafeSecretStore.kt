package com.codingpit.muviss.core.secrets

import com.codingpit.muviss.core.common.secrets.SecretStore
import eu.anifantakis.lib.ksafe.KSafe

/**
 * [SecretStore] over KSafe (ADR 0027): AES-256-GCM with the key held by the
 * platform — Android Keystore, iOS Keychain, the desktop OS store, WebCrypto.
 */
internal class KSafeSecretStore(private val ksafe: KSafe) : SecretStore {
    override suspend fun get(name: String): String? = ksafe.get<String?>(name, null)

    override suspend fun put(name: String, value: String) {
        ksafe.put(name, value)
    }

    override suspend fun clear(name: String) {
        ksafe.delete(name)
    }

    override suspend fun clearAll() {
        ksafe.clearAll()
    }
}
