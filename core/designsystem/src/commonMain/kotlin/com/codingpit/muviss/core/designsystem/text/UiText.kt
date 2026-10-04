// Compose Resources' lookups only take format arguments as a vararg, so the
// spread is the API's shape, not a choice; argument lists are a handful long.
@file:Suppress("SpreadOperator")

package com.codingpit.muviss.core.designsystem.text

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * User-visible copy that has not been rendered into a language yet (EPIC 31, #74).
 *
 * A view model's state holds a `UiText`, never an English `String`: the
 * locale is a property of the composition (and, on Android, can change under a
 * live view model when the user picks another app language), so the string is
 * only looked up where it is drawn. [Raw] is for text that is already in the
 * user's language because it did not come from us — a TMDB title, a list name
 * the user typed — not an escape hatch for copy.
 *
 * Arguments may themselves be [UiText]; they are resolved first, so
 * "Couldn't load %1$s" can take a localized noun.
 */
sealed interface UiText {
    data class Resource(val resource: StringResource, val args: List<Any> = emptyList()) : UiText {
        constructor(resource: StringResource, vararg args: Any) : this(resource, args.toList())
    }

    data class Plural(
        val resource: PluralStringResource,
        val quantity: Int,
        val args: List<Any> = listOf(quantity),
    ) : UiText

    data class Raw(val value: String) : UiText
}

/** Renders this text in the composition's locale. */
@Composable
fun UiText.resolve(): String = when (this) {
    is UiText.Raw -> value
    is UiText.Resource -> stringResource(resource, *args.map { it.resolveArg() }.toTypedArray())
    is UiText.Plural -> pluralStringResource(resource, quantity, *args.map { it.resolveArg() }.toTypedArray())
}

/**
 * Off-composition rendering for the places that have no composition — a
 * desktop notification, a widget update — in the current resource locale.
 */
suspend fun UiText.resolveAsync(): String = when (this) {
    is UiText.Raw -> value
    is UiText.Resource -> getString(resource, *args.map { it.resolveArgAsync() }.toTypedArray())
    is UiText.Plural -> getPluralString(resource, quantity, *args.map { it.resolveArgAsync() }.toTypedArray())
}

@Composable
private fun Any.resolveArg(): Any = if (this is UiText) resolve() else this

private suspend fun Any.resolveArgAsync(): Any = if (this is UiText) resolveAsync() else this
