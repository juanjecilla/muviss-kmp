package com.codingpit.muviss

import coil3.PlatformContext
import coil3.disk.DiskCache
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * Coil's disk cache under the app's Caches directory, not the temporary one.
 *
 * iOS empties tmp whenever it likes, including mid-session, so a poster cache
 * there is a cache in name only. Caches survives launches and is purged only
 * under disk pressure, which is the trade a poster cache wants. It is excluded
 * from iCloud backup by definition, so it does not bloat a restore.
 */
internal actual fun imageDiskCache(context: PlatformContext): DiskCache? = DiskCache.Builder()
    .directory(imageCacheDirectory())
    .maxSizeBytes(IMAGE_DISK_CACHE_BYTES)
    .build()

/** The image cache's directory under the app's Caches root. */
internal fun imageCacheDirectory(cachesRoot: String = iosCachesRoot()): Path = "$cachesRoot/$IMAGE_CACHE_DIRECTORY".toPath()

/**
 * The app's Caches directory. The temporary directory is only the fallback for
 * the impossible case where the system reports no Caches directory at all.
 */
internal fun iosCachesRoot(): String = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
    .firstOrNull() as? String
    ?: FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString()

private const val IMAGE_CACHE_DIRECTORY = "muviss_image_cache"
