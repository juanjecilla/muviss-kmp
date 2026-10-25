package com.codingpit.muviss.core.common.platform

import platform.UIKit.UIDevice

actual val platformName: String = "ios"

actual val platformOsVersion: String = UIDevice.currentDevice.systemVersion
