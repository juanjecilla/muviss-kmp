package com.codingpit.muviss

import coil3.PlatformContext
import coil3.disk.DiskCache
import okio.Path.Companion.toOkioPath

internal actual fun imageDiskCache(context: PlatformContext): DiskCache? = DiskCache.Builder()
    .directory(context.cacheDir.resolve(IMAGE_CACHE_DIRECTORY).toOkioPath())
    .maxSizeBytes(IMAGE_DISK_CACHE_BYTES)
    .build()

private const val IMAGE_CACHE_DIRECTORY = "muviss_image_cache"
