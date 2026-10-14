package com.codingpit.muviss.core.designsystem.text

import androidx.compose.runtime.Composable
import com.codingpit.muviss.core.common.flags.SnoozePeriod
import com.codingpit.muviss.core.common.flags.SnoozePlacement
import com.codingpit.muviss.core.designsystem.generated.resources.Res
import com.codingpit.muviss.core.designsystem.generated.resources.snooze_period_ask
import com.codingpit.muviss.core.designsystem.generated.resources.snooze_period_one_month
import com.codingpit.muviss.core.designsystem.generated.resources.snooze_period_one_week
import com.codingpit.muviss.core.designsystem.generated.resources.snooze_period_three_months
import com.codingpit.muviss.core.designsystem.generated.resources.snooze_placement_first
import com.codingpit.muviss.core.designsystem.generated.resources.snooze_placement_last
import com.codingpit.muviss.core.designsystem.generated.resources.snooze_placement_mixed_in
import org.jetbrains.compose.resources.stringResource

/**
 * A [SnoozePeriod] as the user reads it. Lives here rather than in either
 * feature so the Settings picker and the deck's own "how long?" dialog cannot
 * drift apart (EPIC 42), now that the copy is translated (EPIC 31, #74).
 */
@Composable
fun SnoozePeriod.label(): String = stringResource(
    when (this) {
        SnoozePeriod.ONE_WEEK -> Res.string.snooze_period_one_week
        SnoozePeriod.ONE_MONTH -> Res.string.snooze_period_one_month
        SnoozePeriod.THREE_MONTHS -> Res.string.snooze_period_three_months
        SnoozePeriod.ASK_EACH_TIME -> Res.string.snooze_period_ask
    },
)

/** A [SnoozePlacement] as the user reads it; see [SnoozePeriod.label]. */
@Composable
fun SnoozePlacement.label(): String = stringResource(
    when (this) {
        SnoozePlacement.MIXED_IN -> Res.string.snooze_placement_mixed_in
        SnoozePlacement.FIRST -> Res.string.snooze_placement_first
        SnoozePlacement.LAST -> Res.string.snooze_placement_last
    },
)
