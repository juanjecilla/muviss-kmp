package com.codingpit.muviss

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade

/** Upper bound for the poster/still cache on disk. */
internal const val IMAGE_DISK_CACHE_BYTES: Long = 100L * 1024 * 1024

/**
 * The app's Coil [ImageLoader]: the Ktor fetcher, a crossfade so artwork
 * fades in rather than popping, and an explicit [IMAGE_DISK_CACHE_BYTES] disk
 * cache where the platform has a file system to put one on.
 *
 * Coil would otherwise size its own default (a percentage of free space, capped
 * at 250 MB), which is not something the app can reason about or test.
 */
internal fun createImageLoader(context: PlatformContext): ImageLoader = ImageLoader
    .Builder(context)
    .components { add(KtorNetworkFetcherFactory()) }
    .crossfade(true)
    .diskCache { imageDiskCache(context) }
    .build()

/**
 * A [IMAGE_DISK_CACHE_BYTES] disk cache in the platform's cache location, or
 * null where there is no file system to use: on web (both Js and Wasm) okio has
 * no `FileSystem.SYSTEM`, so images are cached in memory by Coil and by the
 * browser's own HTTP cache only.
 */
internal expect fun imageDiskCache(context: PlatformContext): DiskCache?
