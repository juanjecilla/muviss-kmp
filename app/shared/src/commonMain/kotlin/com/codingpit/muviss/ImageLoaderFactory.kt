package com.codingpit.muviss

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import com.codingpit.muviss.core.network.createHttpClient
import io.ktor.client.HttpClient
import org.koin.mp.KoinPlatformTools

/** Upper bound for the poster/still cache on disk. */
internal const val IMAGE_DISK_CACHE_BYTES: Long = 100L * 1024 * 1024

/**
 * The app's Coil [ImageLoader]: the Ktor fetcher over [sharedImageHttpClient]
 * (issue #94 — previously `KtorNetworkFetcherFactory()`'s own default client:
 * its own engine, its own connection pool, no timeouts, no User-Agent), a
 * crossfade so artwork fades in rather than popping, and an explicit
 * [IMAGE_DISK_CACHE_BYTES] disk cache where the platform has a file system to
 * put one on.
 *
 * Coil would otherwise size its own default (a percentage of free space, capped
 * at 250 MB), which is not something the app can reason about or test.
 */
internal fun createImageLoader(context: PlatformContext): ImageLoader = ImageLoader
    .Builder(context)
    .components { add(KtorNetworkFetcherFactory(httpClient = ::sharedImageHttpClient)) }
    .crossfade(true)
    .diskCache { imageDiskCache(context) }
    .build()

/**
 * The [HttpClient] Coil's fetcher makes image requests with: the app's shared
 * base client — same engine/connection pool, timeouts and User-Agent as TMDB
 * requests — but never TMDB's own derived policy (`expectSuccess`, retry,
 * `HttpCache`; see `:core:network`'s network-errors note), and never
 * `TmdbProvider`'s request semaphore, since that only wraps calls made
 * *through* `TmdbProvider` — a burst of posters loading does not queue behind
 * API calls, and a slow poster host does not throw the `MetadataError`s TMDB
 * callers expect.
 *
 * `KtorNetworkFetcherFactory`'s `httpClient` parameter is itself a supplier,
 * resolved lazily the first time Coil needs it rather than at
 * [createImageLoader]'s call site — which matters here because that call site
 * (`MuvissApp`'s `remember { configureImageLoader() }`) runs *before*
 * `KoinApplication` starts. Android, iOS and desktop each start their own Koin
 * graph before Compose ever runs (`MuvissApplication.onCreate`,
 * `IosAppStartup.start`, desktop's `main`), so by the time an image actually
 * loads — necessarily after `KoinApplication`'s content composes — the graph
 * is up on every target, web included (there, `KoinApplication` starts it
 * synchronously, still ahead of any screen with an image). [KoinPlatformTools]
 * (not `koinInject`, which needs a composition, and not `org.koin.core.context.GlobalContext`,
 * which is a JVM-only actual — see `IosAppStartup`'s doc for the same reason
 * it uses this instead) is what reaches that graph from here; the
 * `createHttpClient()` fallback only matters for a caller with no Koin graph
 * at all (a Compose preview, a unit test), where it still returns a
 * correctly-configured client rather than crashing image loading.
 */
internal fun sharedImageHttpClient(): HttpClient = KoinPlatformTools.defaultContext().getOrNull()?.getOrNull<HttpClient>() ?: createHttpClient()

/**
 * A [IMAGE_DISK_CACHE_BYTES] disk cache in the platform's cache location, or
 * null where there is no file system to use: on web (both Js and Wasm) okio has
 * no `FileSystem.SYSTEM`, so images are cached in memory by Coil and by the
 * browser's own HTTP cache only.
 */
internal expect fun imageDiskCache(context: PlatformContext): DiskCache?
