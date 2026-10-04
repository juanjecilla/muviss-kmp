package com.codingpit.muviss.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * Flags English copy written inline in UI code (EPIC 31, #74): a string
 * literal passed to `Text(...)`, or to a named argument that is always
 * user-visible (`contentDescription = "..."`, `label = "..."`, ...).
 *
 * User-visible copy lives in the module's `composeResources/values/strings.xml`
 * (with `values-es` beside it) and is read with `stringResource(Res.string.x)`,
 * or travels out of a view model as `UiText` (`:core:designsystem`). A literal
 * compiles, renders and passes every English test, and is invisible until a
 * Spanish user opens the screen — so this rule, not review, is what keeps a
 * migrated module migrated.
 *
 * Only literals with a letter in their *literal* parts count: `"$a · $b"` and
 * `"›"` carry no language, `"${n} titles"` does. Syntactic like
 * [ForbiddenViewModelScopeLaunch], so it runs in the non-type-resolution mode
 * the pre-commit hook uses.
 *
 * Scoped in `config/detekt/detekt.yml`: `includes` limits it to `commonMain`
 * of UI modules, and `excludes` lists the modules whose strings have not been
 * migrated yet. Each migration PR deletes its module from that list; the list
 * only ever shrinks.
 */
class HardcodedUiCopy(config: Config = Config.empty) : Rule(config) {

    override val issue: Issue = Issue(
        id = javaClass.simpleName,
        severity = Severity.Maintainability,
        description = "User-visible text is hardcoded — move it to composeResources/values/strings.xml " +
            "(and values-es) and read it with stringResource(Res.string.x), or pass a UiText.",
        debt = Debt.FIVE_MINS,
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val callee = expression.calleeExpression?.text.orEmpty()
        val isText = callee == "Text"
        expression.valueArguments.forEachIndexed { index, argument ->
            val name = argument.getArgumentName()?.asName?.identifier
            val copyBearing = when {
                name == "label" && callee.isAnimationApi() -> false
                name != null -> name in COPY_ARGUMENTS
                else -> isText && index == 0
            }
            if (copyBearing) argument.reportIfLanguage()
        }
    }

    private fun KtValueArgument.reportIfLanguage() {
        val template = getArgumentExpression() as? KtStringTemplateExpression ?: return
        val hasLanguage = template.entries
            .filterIsInstance<KtLiteralStringTemplateEntry>()
            .any { entry -> entry.text.any(Char::isLetter) }
        if (hasLanguage) report(CodeSmell(issue, Entity.from(template), issue.description))
    }

    /** Compose's animation APIs take a `label` too — a tooling name, never shown to anyone. */
    private fun String.isAnimationApi(): Boolean = startsWith("animate") || startsWith("Animated") || this in ANIMATION_APIS

    private companion object {
        val ANIMATION_APIS = setOf("rememberInfiniteTransition", "updateTransition", "rememberTransition", "Crossfade")

        /** Named arguments that reach the screen or the screen reader in every API that takes them. */
        val COPY_ARGUMENTS = setOf(
            "text",
            "contentDescription",
            "label",
            "placeholder",
            "title",
            "subtitle",
            "message",
            "body",
            "actionLabel",
            "supportingText",
            "onClickLabel",
            "stateDescription",
        )
    }
}
