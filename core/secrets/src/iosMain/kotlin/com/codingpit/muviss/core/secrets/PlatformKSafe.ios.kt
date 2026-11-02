package com.codingpit.muviss.core.secrets

import eu.anifantakis.lib.ksafe.KSafe

// Keychain items are scoped to the app by the OS.
internal actual fun createPlatformKSafe(): KSafe = KSafe(fileName = SECRETS_FILE)
