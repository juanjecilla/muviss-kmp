package com.codingpit.muviss.feature.profile.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.codingpit.muviss.core.designsystem.theme.MuvissChartPalette
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.profile.domain.GenreCount
import com.codingpit.muviss.feature.profile.domain.MonthlyRewatches
import com.codingpit.muviss.feature.profile.domain.StatusBreakdown
import com.codingpit.muviss.feature.profile.ui.generated.resources.Res
import com.codingpit.muviss.feature.profile.ui.generated.resources.a11y_genre_summary
import com.codingpit.muviss.feature.profile.ui.generated.resources.genre_other
import com.codingpit.muviss.feature.profile.ui.generated.resources.genre_tags
import com.codingpit.muviss.feature.profile.ui.generated.resources.month_initials
import com.codingpit.muviss.feature.profile.ui.generated.resources.status_finished
import com.codingpit.muviss.feature.profile.ui.generated.resources.status_not_started
import com.codingpit.muviss.feature.profile.ui.generated.resources.status_watched
import com.codingpit.muviss.feature.profile.ui.generated.resources.status_watching
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Compose-canvas-only bar/donut charts for the profile stats section (EPIC 4
// — no chart library allowed). Colors come from the design system's
// MuvissChartPalette so the same status/genre always reads as the same color
// in both themes; a 7th+ genre folds into "Other" rather than generating a
// new hue. Charts grow in once per screen visit (600ms, emphasized
// decelerate) — guarded by rememberSaveable so config changes don't replay.

private val OtherGenreColor = Color(0xFF898781)
private const val MAX_GENRE_SLOTS = 6

/** M3 emphasized-decelerate; 600ms per the design doc's chart-entry motion row. */
private val ChartEntryEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private const val CHART_ENTRY_MS = 600

// Component dimensions, not spacing — deliberately off the 4dp grid where the
// grid would change how the mark reads. MuvissSpacing covers the gaps between
// these, never the marks themselves.
private val STATUS_BAR_HEIGHT = 14.dp
private val STATUS_LABEL_WIDTH = 88.dp
private val STATUS_COUNT_WIDTH = 28.dp
private val LEGEND_SWATCH = 10.dp

/** 0→1 grow-in fraction, animated only on the first composition of this screen visit. */
@Composable
private fun rememberChartGrowth(): State<Float> {
    val played = rememberSaveable { mutableStateOf(false) }
    val growth = remember { Animatable(if (played.value) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!played.value) {
            growth.animateTo(1f, tween(CHART_ENTRY_MS, easing = ChartEntryEasing))
            played.value = true
        }
    }
    return growth.asState()
}

/** Horizontal bars, one per [StatusBreakdown] bucket, each label + count always shown (never color-only identity). */
@Composable
fun StatusBarChart(breakdown: StatusBreakdown, modifier: Modifier = Modifier) {
    val palette = MuvissChartPalette.categorical()
    val growth by rememberChartGrowth()
    val entries = listOf(
        stringResource(Res.string.status_not_started) to breakdown.notStarted,
        stringResource(Res.string.status_watching) to breakdown.watching,
        stringResource(Res.string.status_watched) to breakdown.watched,
        stringResource(Res.string.status_finished) to breakdown.finished,
    )
    val maxCount = entries.maxOf { it.second }.coerceAtLeast(1)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(MuvissSpacing.m)) {
        entries.forEachIndexed { index, (label, count) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
                Text(label, modifier = Modifier.width(STATUS_LABEL_WIDTH), style = MaterialTheme.typography.labelSmall)
                val fraction = count / maxCount.toFloat()
                val barColor = palette[index % palette.size]
                Canvas(Modifier.weight(1f).height(STATUS_BAR_HEIGHT)) {
                    val corner = CornerRadius(size.height / 2f, size.height / 2f)
                    drawRoundRect(color = barColor.copy(alpha = 0.18f), cornerRadius = corner)
                    if (fraction > 0f) {
                        drawRoundRect(
                            color = barColor,
                            size = size.copy(width = size.width * fraction * growth),
                            cornerRadius = corner,
                        )
                    }
                }
                Text(
                    count.toString(),
                    modifier = Modifier.width(STATUS_COUNT_WIDTH),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/** Caps [genres] to [MAX_GENRE_SLOTS] direct-labeled entries, folding the remainder into a single "Other" bucket. */
fun foldGenresIntoOther(genres: List<GenreCount>, maxSlots: Int = MAX_GENRE_SLOTS): List<GenreCount> {
    if (genres.size <= maxSlots) return genres
    val kept = genres.take(maxSlots - 1)
    val otherCount = genres.drop(maxSlots - 1).sumOf { it.count }
    return kept + GenreCount(genre = OTHER_GENRE, count = otherCount)
}

// ---------------------------------------------------------------------------
// Genre donut
// ---------------------------------------------------------------------------

const val GENRE_DONUT_TAG = "genre_donut"
const val GENRE_DONUT_CENTER_TAG = "genre_donut_center"

/** Test tag for one legend row, so a test can tap a genre by name rather than by index. */
fun genreLegendTag(genre: String): String = "genre_legend_$genre"

private val DONUT_SIZE = 160.dp

/**
 * The wide layout's legend stops growing here (#64). Its two columns used to
 * share whatever the row had left, so on a tablet each row, and a selected
 * row's highlight, stretched ~500dp past a label like "Comedy (7)". Two
 * ~200dp columns hold the longest genre names; the rows keep their height,
 * which is what makes a thin slice selectable at all.
 */
private val WIDE_LEGEND_MAX_WIDTH = 420.dp

/**
 * Ring thickness as a fraction of the donut's width. Thinner than the 0.24
 * this chart used at 96dp: at 160dp the old fraction left a 71dp hole, and
 * the center label has to live in there.
 */
private const val DONUT_STROKE_FRACTION = 0.18f

/** How much thicker the selected arc is drawn than its neighbours. */
private val SELECTED_STROKE_BONUS = 6.dp

/** Widest the center label may be; the hole is ~90dp across at [DONUT_SIZE]. */
private val DONUT_LABEL_WIDTH = 88.dp

private const val UNSELECTED_ALPHA = 0.3f

/** Two columns of legend entries, in both the stacked and the side-by-side layout. */
private const val LEGEND_COLUMNS = 2

/** Below this the legend goes under the donut; at or above it, beside it. Matches MuvissApp's nav-rail switch. */
private val WIDE_LAYOUT_BREAKPOINT = WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND

/**
 * Whether the window is wide enough to put the legend beside the ring.
 *
 * Overridable by callers because this reads the *window*, and a screenshot
 * test renders a fixed-size frame inside a window of whatever size the host
 * felt like — see `GoldenSurface`'s KDoc. Left to the default, a 412dp golden
 * frame would still capture the wide layout, and it would capture a different
 * one on a machine whose test window is narrower.
 */
@Composable
fun rememberWideChartLayout(): Boolean = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(WIDE_LAYOUT_BREAKPOINT)

/**
 * Which slice of a donut of [size] a tap at [tap] landed on, or null for the
 * hole in the middle and for the corners outside the ring — both of which the
 * caller treats as "clear the selection".
 *
 * [sweeps] are the *final* sweep angles in draw order, degrees, clockwise from
 * twelve o'clock. Passing the final angles rather than the animated ones is
 * deliberate: during the 600ms grow-in a tap should select the slice the user
 * aimed at, not whichever one happens to be under the finger mid-sweep.
 *
 * [stroke] is the widest stroke the ring can draw — the selected slice's — so
 * the whole band stays tappable whatever is currently selected.
 *
 * Pure on purpose: this is the part of the chart with real edge cases, and it
 * unit-tests without Compose.
 */
internal fun sliceIndexAt(tap: Offset, size: Size, sweeps: List<Float>, stroke: Float): Int? {
    if (sweeps.isEmpty()) return null
    val outer = size.minDimension / 2f
    val inner = outer - stroke
    val dx = tap.x - size.width / 2f
    val dy = tap.y - size.height / 2f
    val radius = sqrt(dx * dx + dy * dy)
    if (radius < inner || radius > outer) return null

    // atan2 puts 0° at three o'clock; the ring starts at twelve and runs clockwise.
    var degrees = atan2(dy, dx) * 180f / PI.toFloat() + 90f
    if (degrees < 0f) degrees += 360f

    var start = 0f
    for (index in sweeps.indices) {
        // The last slice is closed at 360° rather than at the accumulated sum:
        // the sweeps are floats derived from a division and land a hair short,
        // which would otherwise leave a dead wedge just before twelve o'clock.
        val end = if (index == sweeps.lastIndex) 360f else start + sweeps[index]
        if (degrees >= start && degrees < end) return index
        start += sweeps[index]
    }
    return null
}

/**
 * A slice's share of the whole, rounded to a percent. A non-empty slice that
 * rounds to zero reads "<1%" rather than "0%" — the donut is showing it, so
 * claiming it is nothing contradicts the picture.
 */
internal fun sharePercentLabel(count: Int, total: Int): String {
    if (total <= 0) return "0%"
    val rounded = (count * 100.0 / total).roundToInt()
    return if (rounded == 0 && count > 0) "<1%" else "$rounded%"
}

/**
 * Donut of [genres] (already folded via [foldGenresIntoOther]) with a
 * color-keyed legend, holding its own selection.
 *
 * The selection is keyed on the genre names, not just remembered: the stats are
 * recomputed from the library, so a refresh can re-sort [genres] and an index
 * that meant "Action" would quietly start meaning "Comedy". Changing the set of
 * genres resets to the un-selected state instead.
 */
@Composable
fun GenreDonutChart(
    genres: List<GenreCount>,
    modifier: Modifier = Modifier,
    wide: Boolean = rememberWideChartLayout(),
) {
    if (genres.isEmpty()) return
    var selected by rememberSaveable(genres.map { it.genre }) { mutableStateOf<Int?>(null) }
    GenreDonutChart(
        genres = genres,
        selectedIndex = selected,
        onSelect = { selected = it },
        wide = wide,
        modifier = modifier,
    )
}

/**
 * The drawing half of [GenreDonutChart], with selection and layout branch
 * hoisted.
 *
 * [wide] is a parameter rather than a `currentWindowAdaptiveInfo()` call in
 * here because a screenshot test renders into a fixed frame inside a window
 * of whatever size the host felt like — see `GoldenSurface`'s KDoc. Reading
 * the window here would make both layouts capture identically.
 */
@Composable
fun GenreDonutChart(
    genres: List<GenreCount>,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    if (genres.isEmpty()) return
    val palette = MuvissChartPalette.categorical()
    val growth by rememberChartGrowth()
    val total = genres.sumOf { it.count }.coerceAtLeast(1)
    val sweeps = genres.map { 360f * it.count / total }

    if (wide) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.xl)) {
            GenreDonut(genres, sweeps, selectedIndex, onSelect, palette, growth, total)
            GenreLegend(genres, selectedIndex, onSelect, palette, Modifier.weight(1f, fill = false).widthIn(max = WIDE_LEGEND_MAX_WIDTH))
        }
    } else {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MuvissSpacing.l), horizontalAlignment = Alignment.CenterHorizontally) {
            GenreDonut(genres, sweeps, selectedIndex, onSelect, palette, growth, total)
            GenreLegend(genres, selectedIndex, onSelect, palette, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun GenreDonut(
    genres: List<GenreCount>,
    sweeps: List<Float>,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    palette: List<Color>,
    growth: Float,
    total: Int,
) {
    // What the ring shows, said in one line (EPIC 31b, #164). The legend rows
    // stay the selectable nodes; this is the overview a sighted user gets at a glance.
    val parts = genres.map { "${it.displayName()} ${sharePercentLabel(it.count, total)}" }
    val summary = stringResource(Res.string.a11y_genre_summary, parts.joinToString(", "))
    Box(Modifier.size(DONUT_SIZE), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .matchParentSize()
                .pointerInput(sweeps, selectedIndex) {
                    detectTapGestures { offset ->
                        val canvas = Size(size.width.toFloat(), size.height.toFloat())
                        val maxStroke = canvas.minDimension * DONUT_STROKE_FRACTION + SELECTED_STROKE_BONUS.toPx()
                        val hit = sliceIndexAt(offset, canvas, sweeps, maxStroke)
                        // Tapping the selected slice clears it, as does the hole
                        // and the corners outside the ring.
                        onSelect(if (hit != null && hit != selectedIndex) hit else null)
                    }
                }
                .clearAndSetSemantics {
                    testTag = GENRE_DONUT_TAG
                    contentDescription = summary
                },
        ) {
            val base = size.minDimension * DONUT_STROKE_FRACTION
            val maxStroke = base + SELECTED_STROKE_BONUS.toPx()
            // Stroke is centred on the path, so half of it falls outside the
            // rect it is drawn into. Insetting by half the *widest* stroke keeps
            // every slice inside the box and leaves room for the selected one to
            // thicken without anything re-laying out.
            val inset = maxStroke / 2f
            val arcSize = Size(size.minDimension - maxStroke, size.minDimension - maxStroke)
            var startAngle = -90f
            genres.forEachIndexed { index, genre ->
                val selected = index == selectedIndex
                val sweep = sweeps[index] * growth
                drawArc(
                    color = genre.colorFor(index, palette)
                        .copy(alpha = if (selectedIndex == null || selected) 1f else UNSELECTED_ALPHA),
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = if (selected) maxStroke else base),
                )
                startAngle += sweep
            }
        }
        DonutCenterLabel(genres.getOrNull(selectedIndex ?: -1), total)
    }
}

/**
 * The middle of the ring: the whole it divides when nothing is picked, the
 * picked slice otherwise.
 *
 * The resting number is the sum of the genre counts, and it is captioned
 * "genre tags" rather than "titles" because that is what it is — a title
 * carrying three genres lands in three buckets. It is also the only
 * denominator the slice percentages are true against.
 */
@Composable
private fun DonutCenterLabel(selected: GenreCount?, total: Int) {
    Column(
        Modifier.width(DONUT_LABEL_WIDTH).testTag(GENRE_DONUT_CENTER_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = selected?.displayName() ?: total.toString(),
            style = if (selected == null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = selected?.let { "${it.count} · ${sharePercentLabel(it.count, total)}" } ?: stringResource(Res.string.genre_tags),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * Two columns of selectable legend rows.
 *
 * These are the accessible — and often the only usable — way to pick a genre.
 * A slice's arc is as small as its share, and a fifth-place genre can be one
 * title in five hundred; the row for it is still a full-width 48dp target.
 */
@Composable
private fun GenreLegend(
    genres: List<GenreCount>,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    palette: List<Color>,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MuvissSpacing.xs)) {
        genres.indices.chunked(LEGEND_COLUMNS).forEach { rowIndices ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
                rowIndices.forEach { index ->
                    GenreLegendRow(
                        genre = genres[index],
                        color = genres[index].colorFor(index, palette),
                        selected = index == selectedIndex,
                        onClick = { onSelect(if (index == selectedIndex) null else index) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(LEGEND_COLUMNS - rowIndices.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun GenreLegendRow(
    genre: GenreCount,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(MuvissSpacing.s)
    Row(
        modifier
            .heightIn(min = MuvissSpacing.huge)
            .clip(shape)
            .selectable(selected = selected, onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
                shape,
            )
            .padding(horizontal = MuvissSpacing.s, vertical = MuvissSpacing.xs)
            .testTag(genreLegendTag(genre.genre)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.s),
    ) {
        Box(Modifier.size(LEGEND_SWATCH).background(color, CircleShape))
        Text(
            "${genre.displayName()} (${genre.count})",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun GenreCount.colorFor(index: Int, palette: List<Color>): Color = if (genre == OTHER_GENRE) OtherGenreColor else palette[index % palette.size]

const val REWATCH_TREND_TAG = "rewatch_trend"

/**
 * Rewatches per month over the trailing year, one bar each, empty months
 * included so a quiet stretch reads as a gap rather than closing up.
 *
 * Labelled with single initials: twelve three-letter month names do not fit
 * across a phone, and a year stamp on January does not either — at a twelfth
 * of the width it clips to "202". The heading carries the span instead, and a
 * run of twelve months ending at the current one is unambiguous from that.
 */
@Composable
fun RewatchTrendChart(months: List<MonthlyRewatches>, modifier: Modifier = Modifier) {
    if (months.isEmpty()) return
    val barColor = MuvissChartPalette.categorical().first()
    val growth by rememberChartGrowth()
    val maxCount = months.maxOf { it.rewatches }.coerceAtLeast(1)
    val monthInitials = stringResource(Res.string.month_initials).split(',')

    Column(modifier, verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s)) {
        Row(
            Modifier.fillMaxWidth().height(TREND_HEIGHT).testTag(REWATCH_TREND_TAG),
            horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.xs),
            verticalAlignment = Alignment.Bottom,
        ) {
            months.forEach { month ->
                val fraction = month.rewatches / maxCount.toFloat()
                Canvas(Modifier.weight(1f).height(TREND_HEIGHT)) {
                    val corner = CornerRadius(size.width / 4f, size.width / 4f)
                    drawRoundRect(color = barColor.copy(alpha = 0.18f), cornerRadius = corner)
                    if (fraction > 0f) {
                        val barHeight = size.height * fraction * growth
                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset(0f, size.height - barHeight),
                            size = size.copy(height = barHeight),
                            cornerRadius = corner,
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.xs)) {
            months.forEach { month ->
                Text(
                    text = monthInitials[month.month - 1],
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val TREND_HEIGHT = 96.dp

/** The sentinel [foldGenresIntoOther] names its bucket with; rendered through [displayName], never shown as is. */
const val OTHER_GENRE = "Other"

/** A genre as the user reads it: TMDB names pass through, the folded bucket is translated. */
@Composable
private fun GenreCount.displayName(): String = if (genre == OTHER_GENRE) stringResource(Res.string.genre_other) else genre
