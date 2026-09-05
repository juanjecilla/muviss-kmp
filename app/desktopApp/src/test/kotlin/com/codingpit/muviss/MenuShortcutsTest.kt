package com.codingpit.muviss

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The menu bar itself cannot be tested here — a Compose Desktop `MenuBar` is
 * rendered by the platform's own menu system rather than by Skiko, so
 * `runComposeUiTest` cannot see it (the same reason `CLAUDE.md` records for
 * Glance on Android). What *is* testable is the shortcut table it reads, and
 * both things below have already been wrong once.
 *
 * `KeyShortcut`'s `ctrl`/`meta` accessors are internal to the Compose `ui`
 * module, so these compare whole `KeyShortcut` values — it implements `equals`
 * and `toString`, which is enough for both the assertion and a readable
 * failure.
 */
class MenuShortcutsTest {

    /**
     * Recomputed here rather than read from `Main.kt`'s `isMacOs`, on purpose.
     *
     * Asserting `shortcut.meta == isMacOs` would pass for any value of
     * `isMacOs` — including a wrong one — because the assertion and the code
     * under test would be reading the same variable. Deriving the expectation
     * from `os.name` independently is what makes these assertions say
     * something about this machine rather than about themselves.
     */
    private val onMacOs: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac")

    private fun expected(key: Key) = KeyShortcut(key, meta = onMacOs, ctrl = !onMacOs)

    @Test
    fun `search is bound to the platform's primary modifier plus F`() {
        assertEquals(expected(Key.F), viewShortcuts["Search"])
    }

    @Test
    fun `settings is bound to the platform's primary modifier plus comma`() {
        assertEquals(expected(Key.Comma), viewShortcuts["Settings"])
    }

    @Test
    fun `the accelerator helper follows the same rule`() {
        assertEquals(expected(Key.F), accelerator(Key.F))
    }

    /**
     * macOS's application menu already carries "Quit <app>" with Cmd+Q, and AWT
     * drops a duplicate: binding it here rendered the item with no accelerator
     * at all. Elsewhere there is no such menu, so File > Quit is the only Quit.
     */
    @Test
    fun `quit is bound everywhere except macOS, which already has it`() {
        if (onMacOs) {
            assertNull(quitShortcut, "macOS's own application menu owns Cmd+Q; a second binding is dropped")
        } else {
            assertEquals(expected(Key.Q), assertNotNull(quitShortcut))
        }
    }

    /**
     * The shortcuts are keyed by [MuvissDestination.label] so that reordering
     * the navigation bar cannot silently move a shortcut onto a different
     * screen. The cost of that choice is that *renaming* a label drops the
     * shortcut instead, equally silently — which is what this catches.
     */
    @Test
    fun `every shortcut names a destination that exists`() {
        val labels = muvissDestinations.map { it.label }
        viewShortcuts.keys.forEach { key ->
            assertTrue(key in labels, "'$key' has a keyboard shortcut but is not a destination; labels are $labels")
        }
    }

    @Test
    fun `the two destinations worth a chord still have one`() {
        assertEquals(setOf("Search", "Settings"), viewShortcuts.keys)
    }
}
