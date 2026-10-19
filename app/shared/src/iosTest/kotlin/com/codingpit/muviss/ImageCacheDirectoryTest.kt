package com.codingpit.muviss

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageCacheDirectoryTest {

    @Test
    fun the_simulator_caches_root_is_the_library_caches_directory() {
        assertTrue(iosCachesRoot().endsWith("/Library/Caches"), iosCachesRoot())
    }

    @Test
    fun the_image_cache_lives_under_caches_not_the_temporary_directory() {
        val cache = imageCacheDirectory().toString()

        assertTrue(cache.startsWith(iosCachesRoot()), cache)
        assertFalse(cache.contains("/tmp/") || cache.contains("/T/"), cache)
    }

    @Test
    fun the_image_cache_is_a_named_subdirectory_of_the_given_root() {
        assertEquals("/var/mobile/Library/Caches/muviss_image_cache", imageCacheDirectory("/var/mobile/Library/Caches").toString())
    }
}
