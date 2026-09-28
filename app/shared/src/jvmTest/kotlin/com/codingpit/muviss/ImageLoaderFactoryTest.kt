package com.codingpit.muviss

import coil3.PlatformContext
import com.codingpit.muviss.core.network.createHttpClient
import io.ktor.client.HttpClient
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class ImageLoaderFactoryTest {

    // sharedImageHttpClient reads GlobalContext directly, so a test that
    // starts its own graph must not leak it into the next test.
    @AfterTest
    fun tearDown() {
        runCatching { stopKoin() }
    }

    @Test
    fun the_disk_cache_is_explicitly_100_MB() {
        val cache = assertNotNull(imageDiskCache(PlatformContext.INSTANCE), "a file system exists on the JVM")

        assertEquals(100L * 1024 * 1024, cache.maxSize)
    }

    @Test
    fun the_image_loader_is_built_with_that_cache() {
        val loader = createImageLoader(PlatformContext.INSTANCE)

        assertNotNull(loader.diskCache)
        assertEquals(IMAGE_DISK_CACHE_BYTES, loader.diskCache?.maxSize)
    }

    @Test
    fun sharedImageHttpClient_falls_back_to_a_fresh_client_with_no_koin_graph_running() {
        // No startKoin() in this test — issue #94's fallback path (a Compose
        // preview, or any caller with no app graph) must not crash.
        val client = sharedImageHttpClient()

        assertNotNull(client)
    }

    @Test
    fun sharedImageHttpClient_reuses_the_app_s_koin_bound_shared_client() {
        val shared = createHttpClient()
        startKoin { modules(module { single<HttpClient> { shared } }) }

        val resolved = sharedImageHttpClient()

        assertSame(shared, resolved, "Coil must reuse the app's shared HttpClient singleton, not build its own")
    }
}
