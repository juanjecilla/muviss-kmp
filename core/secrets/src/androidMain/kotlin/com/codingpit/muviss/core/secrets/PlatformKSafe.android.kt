package com.codingpit.muviss.core.secrets

import android.content.Context
import com.codingpit.muviss.core.common.secrets.SecretStore
import eu.anifantakis.lib.ksafe.KSafe

internal actual fun createPlatformKSafe(): KSafe = error("Android needs a Context: bind SecretStore with androidSecretStore(context) in the Application's Koin module.")

/** The Android [SecretStore]: AES-256-GCM, key in the Android Keystore. */
fun androidSecretStore(context: Context): SecretStore = KSafeSecretStore(KSafe(context.applicationContext, fileName = SECRETS_FILE))
