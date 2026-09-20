package com.codingpit.muviss

import coil3.PlatformContext
import coil3.disk.DiskCache

// No okio FileSystem.SYSTEM on web: Coil's memory cache and the browser's HTTP cache are all there is.
internal actual fun imageDiskCache(context: PlatformContext): DiskCache? = null
