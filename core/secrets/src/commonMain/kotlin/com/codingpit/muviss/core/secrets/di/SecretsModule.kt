package com.codingpit.muviss.core.secrets.di

import com.codingpit.muviss.core.common.secrets.SecretStore
import com.codingpit.muviss.core.secrets.KSafeSecretStore
import com.codingpit.muviss.core.secrets.createPlatformKSafe
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Binds [SecretStore] to KSafe on the targets that can build one without help.
 * Android's host overrides this with `androidSecretStore(context)` — Koin's last
 * binding wins.
 */
val secretsModule: Module = module {
    single<SecretStore> { KSafeSecretStore(createPlatformKSafe()) }
}
