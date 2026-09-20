package com.codingpit.muviss

import coil3.PlatformContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ImageLoaderFactoryTest {

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
}
