package com.codingpit.muviss

import coil3.PlatformContext
import coil3.disk.DiskCache
import okio.FileSystem

internal actual fun imageDiskCache(context: PlatformContext): DiskCache? = DiskCache.Builder()
    .directory(FileSystem.SYSTEM_TEMPORARY_DIRECTORY.resolve(IMAGE_CACHE_DIRECTORY))
    .maxSizeBytes(IMAGE_DISK_CACHE_BYTES)
    .build()

private const val IMAGE_CACHE_DIRECTORY = "muviss_image_cache"
