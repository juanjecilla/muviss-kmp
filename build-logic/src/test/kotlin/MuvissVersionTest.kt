import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MuvissVersionTest {
    @Test
    fun release_candidate_codes_follow_the_formula() {
        assertEquals(1_010_004, MuvissVersion.releaseCode(1, 1, 0, 4))
        assertEquals(1_000_000, MuvissVersion.releaseCode(1, 0, 0, 0))
        assertEquals(1_000_102, MuvissVersion.releaseCode(1, 0, 1, 2))
        assertEquals(2_999_999, MuvissVersion.releaseCode(2, 99, 99, 99))
    }

    @Test
    fun the_next_release_candidate_outranks_the_count_based_codes_already_uploaded() {
        // rc3 of 1.1.0 went up with the commit count (about 176 at the time).
        val rc4 = MuvissVersion.codeFor("v1.1.0-rc4", commitCount = 300)
        assertEquals(1_010_004, rc4)
        assertTrue(rc4!! > 176)
    }

    @Test
    fun codes_rise_with_every_rc_patch_minor_and_major() {
        val ordered = listOf(
            MuvissVersion.releaseCode(1, 0, 0, 1),
            MuvissVersion.releaseCode(1, 0, 0, 2),
            MuvissVersion.releaseCode(1, 0, 1, 0),
            MuvissVersion.releaseCode(1, 0, 1, 1),
            MuvissVersion.releaseCode(1, 1, 0, 0),
            MuvissVersion.releaseCode(1, 1, 0, 1),
            MuvissVersion.releaseCode(1, 1, 0, 4),
            MuvissVersion.releaseCode(1, 10, 0, 0),
            MuvissVersion.releaseCode(2, 0, 0, 0),
        )
        assertEquals(ordered.sorted(), ordered)
        assertEquals(ordered.size, ordered.toSet().size)
    }

    @Test
    fun an_exact_release_tag_uses_the_formula_and_a_plain_final_tag_is_rc_zero() {
        assertEquals(1_010_000, MuvissVersion.codeFor("v1.1.0", commitCount = 300))
        assertEquals(1_010_003, MuvissVersion.codeFor("v1.1.0-rc3", commitCount = 300))
    }

    @Test
    fun anything_else_keeps_the_commit_count() {
        assertEquals(300, MuvissVersion.codeFor(exactTag = null, commitCount = 300))
        assertEquals(300, MuvissVersion.codeFor(exactTag = "v1.1.0-rc3-2-gabc1234", commitCount = 300))
        assertEquals(300, MuvissVersion.codeFor(exactTag = "not-a-version", commitCount = 300))
        assertNull(MuvissVersion.codeFor(exactTag = null, commitCount = null))
    }

    @Test
    fun fields_above_99_are_refused_rather_than_wrapped() {
        assertFailsWith<IllegalArgumentException> { MuvissVersion.releaseCode(1, 100, 0, 0) }
        assertFailsWith<IllegalArgumentException> { MuvissVersion.releaseCode(1, 0, 100, 0) }
        assertFailsWith<IllegalArgumentException> { MuvissVersion.releaseCode(1, 0, 0, 100) }
        assertFailsWith<IllegalArgumentException> { MuvissVersion.releaseCode(-1, 0, 0, 0) }
    }
}
