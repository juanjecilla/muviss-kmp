package com.codingpit.muviss.core.secrets

import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeConfig

// No OS gives a desktop or browser app its own key store, so the namespace keeps
// Muviss's key slot apart from any other app using KSafe on the same machine.
internal actual fun createPlatformKSafe(): KSafe = KSafe(fileName = SECRETS_FILE, config = KSafeConfig(appNamespace = APP_NAMESPACE))
