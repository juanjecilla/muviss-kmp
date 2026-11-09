package com.codingpit.muviss.core.common.platform

import android.os.Build

actual val platformName: String = "android"

actual val platformOsVersion: String = Build.VERSION.RELEASE.orEmpty()
