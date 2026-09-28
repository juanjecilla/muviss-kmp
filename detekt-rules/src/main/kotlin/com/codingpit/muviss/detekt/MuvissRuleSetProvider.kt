package com.codingpit.muviss.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

/**
 * Discovered via `META-INF/services/io.gitlab.arturbosch.detekt.api.RuleSetProvider`
 * (the same `ServiceLoader` mechanism `io.nlopez.compose.rules:detekt` uses),
 * once this module is on the `detektPlugins` configuration of the module being
 * analysed — wired for every module except this one in the root
 * `build.gradle.kts`, to avoid `:detekt-rules` depending on itself.
 */
class MuvissRuleSetProvider : RuleSetProvider {

    override val ruleSetId: String = "muviss"

    override fun instance(config: Config): RuleSet = RuleSet(
        ruleSetId,
        listOf(
            ForbiddenViewModelScopeLaunch(config),
        ),
    )
}
