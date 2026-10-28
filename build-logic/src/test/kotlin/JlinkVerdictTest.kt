import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Proves the jlink module check goes red on a runtime image that cannot run the app (#490). */
class JlinkVerdictTest {

    @Test
    fun a_runtime_that_supplies_everything_the_app_needs_is_ok() {
        val verdict = JlinkVerdict(required = setOf("java.base", "java.desktop", "java.prefs"), declared = setOf("java.desktop", "java.prefs"), resolved = setOf("java.base", "java.desktop", "java.prefs"))
        assertTrue(verdict.ok)
    }

    @Test
    fun a_required_module_the_image_lacks_is_missing() {
        // The first run of this check found java.prefs absent from the declared list.
        val verdict = JlinkVerdict(required = setOf("java.base", "java.sql"), declared = setOf("java.desktop"), resolved = setOf("java.base", "java.desktop"))
        assertFalse(verdict.ok)
        assertEquals(setOf("java.sql"), verdict.missing)
    }

    @Test
    fun a_module_present_only_through_another_root_is_reported_but_not_missing() {
        // java.prefs is required and not declared, but java.desktop requires it.
        val verdict = JlinkVerdict(required = setOf("java.base", "java.prefs"), declared = setOf("java.desktop"), resolved = setOf("java.base", "java.desktop", "java.prefs"))
        assertTrue(verdict.ok)
        assertEquals(setOf("java.prefs"), verdict.transitiveOnly)
    }

    @Test
    fun java_base_is_never_reported_as_transitive_only() {
        val verdict = JlinkVerdict(required = setOf("java.base"), declared = setOf("java.desktop"), resolved = setOf("java.base", "java.desktop"))
        assertTrue(verdict.transitiveOnly.isEmpty())
    }

    @Test
    fun a_declared_root_jdeps_does_not_name_is_reported_not_failed() {
        val verdict = JlinkVerdict(required = setOf("java.base"), declared = setOf("jdk.unsupported"), resolved = setOf("java.base", "jdk.unsupported"))
        assertTrue(verdict.ok)
        assertEquals(setOf("jdk.unsupported"), verdict.unused)
    }

    @Test
    fun an_empty_image_misses_everything_required() {
        val verdict = JlinkVerdict(required = setOf("java.base", "java.desktop"), declared = emptySet(), resolved = emptySet())
        assertEquals(setOf("java.base", "java.desktop"), verdict.missing)
    }
}
