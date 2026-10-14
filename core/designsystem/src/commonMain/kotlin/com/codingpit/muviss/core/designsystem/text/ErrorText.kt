package com.codingpit.muviss.core.designsystem.text

import com.codingpit.muviss.core.designsystem.generated.resources.Res
import com.codingpit.muviss.core.designsystem.generated.resources.error_not_found
import com.codingpit.muviss.core.designsystem.generated.resources.error_offline
import com.codingpit.muviss.core.designsystem.generated.resources.error_rate_limited
import com.codingpit.muviss.core.designsystem.generated.resources.error_unauthorized
import com.codingpit.muviss.core.designsystem.generated.resources.error_unknown
import com.codingpit.muviss.models.MetadataError

/**
 * The translatable version of `toUserMessage` (EPIC 31, #74): a [MetadataError]
 * maps to its own copy in the user's language; anything else maps to
 * [fallback], and its `message` is still discarded — arbitrary exception text
 * can carry a URL, a key or a stack of SQL (EPIC 27).
 *
 * Lives here, once, so every view model reports network failures in the same
 * words; a view model stores the result in its state as [UiText].
 */
fun Throwable.toUiText(fallback: UiText): UiText = when (this) {
    is MetadataError.RateLimited -> UiText.Resource(Res.string.error_rate_limited)
    is MetadataError.Unauthorized -> UiText.Resource(Res.string.error_unauthorized)
    is MetadataError.NotFound -> UiText.Resource(Res.string.error_not_found)
    is MetadataError.Offline -> UiText.Resource(Res.string.error_offline)
    is MetadataError.Unknown -> UiText.Resource(Res.string.error_unknown)
    else -> fallback
}
