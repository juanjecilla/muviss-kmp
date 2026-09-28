package com.codingpit.muviss.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression

/**
 * Flags `viewModelScope.launch { ... }` and `something.launchIn(viewModelScope)`.
 *
 * CLAUDE.md's rule: every `viewModelScope` launch goes through
 * `launchReporting`/`launchInReporting` (`:core:common`), which attach
 * [com.codingpit.muviss.core.common.crash.CrashReportingExceptionHandler] —
 * without it, an exception escaping the launch either takes the app down or,
 * where it was already caught somewhere upstream, is lost, in both cases
 * never reaching Sentry (#107). Nothing in the type system stops a bare
 * `viewModelScope.launch { }` from compiling; it reads identically to the
 * reporting spelling at a glance, which is exactly how it survives review.
 * This rule exists so it cannot survive `detekt` instead.
 *
 * Deliberately syntactic — matched on the receiver/argument *text* being
 * exactly `viewModelScope`, not on the resolved
 * `androidx.lifecycle.viewModelScope` property — so it runs in detekt's
 * normal (non-type-resolution) mode, the one `spotlessCheck detekt` actually
 * runs locally and in the pre-commit hook. Nothing else in this codebase is
 * named `viewModelScope`, so the text match does not need the resolved type
 * to stay precise.
 */
class ForbiddenViewModelScopeLaunch(config: Config = Config.empty) : Rule(config) {

    override val issue: Issue = Issue(
        id = javaClass.simpleName,
        severity = Severity.Defect,
        description = "viewModelScope.launch { } / .launchIn(viewModelScope) bypasses crash reporting — " +
            "use launchReporting { } / .launchInReporting(viewModelScope) (:core:common) instead.",
        debt = Debt.FIVE_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        when (expression.calleeExpression?.text) {
            "launch" -> {
                val receiverText = (expression.parent as? KtDotQualifiedExpression)?.receiverExpression?.text
                if (receiverText == VIEW_MODEL_SCOPE) report(expression)
            }

            "launchIn" -> {
                val argumentText = expression.valueArguments.singleOrNull()?.getArgumentExpression()?.text
                if (argumentText == VIEW_MODEL_SCOPE) report(expression)
            }
        }
    }

    private fun report(expression: KtCallExpression) {
        report(CodeSmell(issue, Entity.from(expression), issue.description))
    }

    private companion object {
        const val VIEW_MODEL_SCOPE = "viewModelScope"
    }
}
