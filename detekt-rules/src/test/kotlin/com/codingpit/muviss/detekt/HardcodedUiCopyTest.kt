package com.codingpit.muviss.detekt

import io.gitlab.arturbosch.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals

/** The rule has to catch copy in every shape it is written in, and nothing that carries no language. */
class HardcodedUiCopyTest {

    private fun findings(code: String) = HardcodedUiCopy().lint(code).size

    @Test
    fun a_positional_text_literal_is_flagged() {
        assertEquals(1, findings("""fun f() { Text("Retry") }"""))
    }

    @Test
    fun a_named_text_literal_is_flagged() {
        assertEquals(1, findings("""fun f() { Text(text = "Retry", color = c) }"""))
    }

    @Test
    fun copy_bearing_named_arguments_are_flagged() {
        assertEquals(
            3,
            findings(
                """
                fun f() {
                    Icon(icon, contentDescription = "Add")
                    NavigationBarItem(label = "Search")
                    Snackbar(message = "Saved", actionLabel = s)
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun a_template_with_words_is_flagged() {
        assertEquals(1, findings("""fun f(n: Int) { Text("${'$'}n titles") }"""))
    }

    @Test
    fun a_template_of_only_arguments_and_punctuation_is_not_flagged() {
        assertEquals(0, findings("""fun f(a: String, b: String) { Text("${'$'}a · ${'$'}b"); Text("›") }"""))
    }

    @Test
    fun a_resource_lookup_is_not_flagged() {
        assertEquals(0, findings("""fun f() { Text(stringResource(Res.string.retry)) }"""))
    }

    @Test
    fun a_variable_is_not_flagged() {
        assertEquals(0, findings("""fun f(title: String) { Text(title); Text(text = title) }"""))
    }

    @Test
    fun a_test_tag_is_not_flagged() {
        assertEquals(0, findings("""fun f() { Box(Modifier.testTag("triage_card")); Foo(tag = "x") }"""))
    }

    @Test
    fun only_the_first_positional_argument_of_text_counts() {
        assertEquals(0, findings("""fun f(s: String) { Text(s, Modifier, "notCopy") }"""))
    }

    @Test
    fun an_animation_label_is_not_flagged() {
        assertEquals(
            0,
            findings(
                """
                fun f() {
                    animateFloatAsState(1f, label = "posterPress")
                    rememberInfiniteTransition(label = "shimmer")
                    transition.animateFloat(0f, 1f, label = "sweep")
                    Crossfade(target, label = "fade")
                    AnimatedContent(target, label = "swap")
                }
                """.trimIndent(),
            ),
        )
    }
}
