package com.codingpit.muviss.detekt

import io.gitlab.arturbosch.detekt.test.compileAndLint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the rule actually catches the pattern #107 exists to catch — a bare
 * `viewModelScope.launch { }`/`.launchIn(viewModelScope)` that compiles and
 * reads identically to the reporting spelling at a glance.
 */
class ForbiddenViewModelScopeLaunchTest {

    private fun lint(code: String) = ForbiddenViewModelScopeLaunch().compileAndLint(code)

    @Test
    fun a_bare_viewModelScope_launch_is_flagged() {
        val findings = lint(
            """
            class SomeViewModel : ViewModel() {
                fun go() {
                    viewModelScope.launch { doWork() }
                }
            }
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun launchIn_viewModelScope_is_flagged() {
        val findings = lint(
            """
            class SomeViewModel : ViewModel() {
                fun go() {
                    someFlow.launchIn(viewModelScope)
                }
            }
            """.trimIndent(),
        )

        assertEquals(1, findings.size)
    }

    @Test
    fun launchReporting_is_not_flagged() {
        val findings = lint(
            """
            class SomeViewModel : ViewModel() {
                fun go() {
                    viewModelScope.launchReporting { doWork() }
                }
            }
            """.trimIndent(),
        )

        assertTrue(findings.isEmpty())
    }

    @Test
    fun launchInReporting_is_not_flagged() {
        val findings = lint(
            """
            class SomeViewModel : ViewModel() {
                fun go() {
                    someFlow.launchInReporting(viewModelScope)
                }
            }
            """.trimIndent(),
        )

        assertTrue(findings.isEmpty())
    }

    @Test
    fun a_launch_on_an_unrelated_scope_is_not_flagged() {
        val findings = lint(
            """
            class SomeRepository {
                fun go(scope: CoroutineScope) {
                    scope.launch { doWork() }
                }
            }
            """.trimIndent(),
        )

        assertTrue(findings.isEmpty())
    }
}
