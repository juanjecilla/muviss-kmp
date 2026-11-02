package com.codingpit.muviss.core.common.secrets

/**
 * Where a value the user entrusted to the app is kept (EPIC 46, ADR 0027):
 * today only an Own TMDB Key. Anything stored here is **never synced and never
 * exported** — it belongs to this device, and a backup that carried it would
 * hand the credential to wherever the file ends up.
 *
 * Deliberately a string-to-string seam: what a value *means* (a v4 token, a v3
 * key) is its owner's business, and the backing store (Keystore, Keychain, the
 * desktop OS store, WebCrypto) is the platform's.
 */
interface SecretStore {
    /** The stored value, or null if nothing is stored under [name]. */
    suspend fun get(name: String): String?

    /** Stores [value] under [name], replacing any previous one. Returns once it is durable. */
    suspend fun put(name: String, value: String)

    /** Removes one value. A no-op if it is absent. */
    suspend fun clear(name: String)

    /**
     * Removes every value *and* whatever key material protected them. This is what
     * "Delete all data" calls: a Keychain item survives an uninstall on iOS, so
     * leaving it would be the one thing that outlives the user's request.
     */
    suspend fun clearAll()
}

/** An in-memory [SecretStore]: what tests, and a build with no secure storage, use. Nothing survives the process. */
class InMemorySecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()

    override suspend fun get(name: String): String? = values[name]

    override suspend fun put(name: String, value: String) {
        values[name] = value
    }

    override suspend fun clear(name: String) {
        values.remove(name)
    }

    override suspend fun clearAll() = values.clear()
}
