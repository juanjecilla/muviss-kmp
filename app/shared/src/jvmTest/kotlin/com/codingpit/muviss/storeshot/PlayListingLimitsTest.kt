package com.codingpit.muviss.storeshot

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Play's field limits on every listing locale (#76), in the plain `jvmTest` a
 * developer runs, so an over-long description fails here rather than at upload
 * time after a full signed build. `scripts/store/check-listing.sh` applies the
 * same limits for the release scripts and `pr-guards`; the two count alike:
 * characters, not bytes, with the newlines left out.
 */
class PlayListingLimitsTest {

    private val limits = mapOf(
        "title.txt" to 30,
        "short_description.txt" to 80,
        "full_description.txt" to 4000,
    )

    @Test
    fun every_listing_locale_fits_plays_limits() {
        val root = System.getProperty("muviss.repo.root") ?: fail("muviss.repo.root is not set")
        val locales = File(root, "fastlane/metadata/android").listFiles { file -> file.isDirectory }.orEmpty()
        val names = locales.map { it.name }
        assertTrue(names.containsAll(REQUIRED_LOCALES), "expected the $REQUIRED_LOCALES listings, found $names")

        val problems = locales.sortedBy { it.name }.flatMap { locale ->
            limits.mapNotNull { (field, max) ->
                val file = File(locale, field)
                val text = file.takeIf { it.isFile }?.readText()?.replace("\n", "").orEmpty()
                val count = text.codePointCount(0, text.length)
                when {
                    count == 0 -> "${locale.name}/$field is missing or empty"
                    count > max -> "${locale.name}/$field is $count characters; Play allows $max"
                    else -> null
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    private companion object {
        val REQUIRED_LOCALES = listOf("en-US", "es-ES")
    }
}
