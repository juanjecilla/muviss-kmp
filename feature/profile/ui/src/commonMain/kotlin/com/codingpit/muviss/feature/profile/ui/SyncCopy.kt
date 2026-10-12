package com.codingpit.muviss.feature.profile.ui

import com.codingpit.muviss.core.designsystem.text.UiText
import com.codingpit.muviss.feature.profile.domain.AutomaticSyncMode
import com.codingpit.muviss.feature.profile.domain.LastSynced
import com.codingpit.muviss.feature.profile.domain.SignInFailure
import com.codingpit.muviss.feature.profile.domain.SyncFailureKind
import com.codingpit.muviss.feature.profile.domain.SyncStatusDetail
import com.codingpit.muviss.feature.profile.ui.generated.resources.Res
import com.codingpit.muviss.feature.profile.ui.generated.resources.sign_in_failure_generic
import com.codingpit.muviss.feature.profile.ui.generated.resources.sign_in_failure_no_code
import com.codingpit.muviss.feature.profile.ui.generated.resources.sign_in_failure_timed_out
import com.codingpit.muviss.feature.profile.ui.generated.resources.sync_automatic_in_background
import com.codingpit.muviss.feature.profile.ui.generated.resources.sync_automatic_when_system_allows
import com.codingpit.muviss.feature.profile.ui.generated.resources.sync_automatic_while_open
import com.codingpit.muviss.feature.profile.ui.generated.resources.sync_changes_waiting
import com.codingpit.muviss.feature.profile.ui.generated.resources.sync_detail_account_changed
import com.codingpit.muviss.feature.profile.ui.generated.resources.sync_detail_failed
import com.codingpit.muviss.feature.profile.ui.generated.resources.sync_failure_offline
import com.codingpit.muviss.feature.profile.ui.generated.resources.sync_failure_server
import com.codingpit.muviss.feature.profile.ui.generated.resources.sync_failure_unauthorised
import com.codingpit.muviss.feature.profile.ui.generated.resources.sync_failure_unknown
import com.codingpit.muviss.feature.profile.ui.generated.resources.sync_last_days_ago
import com.codingpit.muviss.feature.profile.ui.generated.resources.sync_last_hours_ago
import com.codingpit.muviss.feature.profile.ui.generated.resources.sync_last_just_now
import com.codingpit.muviss.feature.profile.ui.generated.resources.sync_last_minutes_ago
import com.codingpit.muviss.feature.profile.ui.generated.resources.sync_last_never
import com.codingpit.muviss.feature.profile.ui.generated.resources.sync_session_expired

/*
 * The sync section's state-dependent wording (#217). The domain returns
 * values (kinds, counts, buckets); this file is the only place that turns
 * them into copy, so it is localized with the rest of the module and never an
 * exception's message.
 */

internal val SyncSessionExpiredText: UiText = UiText.Resource(Res.string.sync_session_expired)

internal fun SyncFailureKind.toUiText(): UiText = UiText.Resource(
    when (this) {
        SyncFailureKind.Offline -> Res.string.sync_failure_offline
        SyncFailureKind.Unauthorised -> Res.string.sync_failure_unauthorised
        SyncFailureKind.Server -> Res.string.sync_failure_server
        SyncFailureKind.Unknown -> Res.string.sync_failure_unknown
    },
)

internal fun SyncStatusDetail.toUiText(): UiText = when (this) {
    SyncStatusDetail.AccountChanged -> UiText.Resource(Res.string.sync_detail_account_changed)
    is SyncStatusDetail.Failed -> UiText.Resource(Res.string.sync_detail_failed, kind.toUiText())
    is SyncStatusDetail.Waiting -> UiText.Plural(Res.plurals.sync_changes_waiting, count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
}

internal fun AutomaticSyncMode.toUiText(): UiText = UiText.Resource(
    when (this) {
        AutomaticSyncMode.InBackground -> Res.string.sync_automatic_in_background
        AutomaticSyncMode.WhenSystemAllows -> Res.string.sync_automatic_when_system_allows
        AutomaticSyncMode.WhileOpen -> Res.string.sync_automatic_while_open
    },
)

internal fun LastSynced.toUiText(): UiText = when (this) {
    LastSynced.Never -> UiText.Resource(Res.string.sync_last_never)
    LastSynced.JustNow -> UiText.Resource(Res.string.sync_last_just_now)
    is LastSynced.MinutesAgo -> UiText.Resource(Res.string.sync_last_minutes_ago, minutes)
    is LastSynced.HoursAgo -> UiText.Resource(Res.string.sync_last_hours_ago, hours)
    is LastSynced.DaysAgo -> UiText.Resource(Res.string.sync_last_days_ago, days)
}

/** [SignInFailure.Declined.reason] is the identity provider's own text, already in its language, hence [UiText.Raw]. */
internal fun SignInFailure.toUiText(): UiText = when (this) {
    SignInFailure.Generic -> UiText.Resource(Res.string.sign_in_failure_generic)
    is SignInFailure.TimedOut -> UiText.Plural(Res.plurals.sign_in_failure_timed_out, minutes)
    SignInFailure.NoCode -> UiText.Resource(Res.string.sign_in_failure_no_code)
    is SignInFailure.Declined -> reason?.takeIf { it.isNotBlank() }?.let(UiText::Raw) ?: UiText.Resource(Res.string.sign_in_failure_generic)
}
