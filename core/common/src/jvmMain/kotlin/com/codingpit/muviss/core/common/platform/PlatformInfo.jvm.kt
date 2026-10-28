package com.codingpit.muviss.core.common.platform

actual val platformName: String = "desktop"

actual val platformOsVersion: String =
    listOfNotNull(System.getProperty("os.name"), System.getProperty("os.version")).joinToString(" ")
