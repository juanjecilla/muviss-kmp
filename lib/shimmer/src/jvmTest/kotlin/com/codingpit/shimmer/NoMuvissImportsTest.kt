package com.codingpit.shimmer

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** This library is meant to leave the Muviss repository, so nothing in it may import Muviss code. */
class NoMuvissImportsTest {

    @Test
    fun the_library_imports_nothing_from_muviss() {
        val source = File("src/commonMain/kotlin")
        assertTrue(source.isDirectory, "run from the module directory: ${source.absolutePath}")
        val offenders = source.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> file.readLines().filter { it.startsWith("import com.codingpit.muviss") }.map { "${file.name}: $it" } }
            .toList()

        assertTrue(offenders.isEmpty(), "Muviss imports in the shimmer library: $offenders")
    }
}
