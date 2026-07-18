package com.codingpit.muviss.feature.profile.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.theme.MuvissChartPalette
import com.codingpit.muviss.feature.profile.domain.GenreCount
import com.codingpit.muviss.feature.profile.domain.StatusBreakdown

// Compose-canvas-only bar/donut charts for the profile stats section (EPIC 4
// — no chart library allowed). Colors come from the design system's
// MuvissChartPalette so the same status/genre always reads as the same color
// in both themes; a 8th+ genre folds into "Other" rather than generating a
// new hue. Charts grow in once per screen visit (600ms, emphasized
// decelerate) — guarded by rememberSaveable so config changes don't replay.

private val OtherGenreColor = Color(0xFF898781)
private const val MAX_GENRE_SLOTS = 6

/** M3 emphasized-decelerate; 600ms per the design doc's chart-entry motion row. */
private val ChartEntryEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private const val CHART_ENTRY_MS = 600

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
        "Not started" to breakdown.notStarted,
        "Watching" to breakdown.watching,
        "Watched" to breakdown.watched,
        "Finished" to breakdown.finished,
    )
    val maxCount = entries.maxOf { it.second }.coerceAtLeast(1)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        entries.forEachIndexed { index, (label, count) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, modifier = Modifier.width(88.dp), style = MaterialTheme.typography.labelSmall)
                val fraction = count / maxCount.toFloat()
                val barColor = palette[index % palette.size]
                Canvas(Modifier.weight(1f).height(14.dp)) {
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
                    modifier = Modifier.width(28.dp),
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
    return kept + GenreCount(genre = "Other", count = otherCount)
}

/** Donut of [genres] (already folded via [foldGenresIntoOther]) with a color-keyed legend beside it; sweeps clockwise on entry. */
@Composable
fun GenreDonutChart(genres: List<GenreCount>, modifier: Modifier = Modifier) {
    if (genres.isEmpty()) return
    val palette = MuvissChartPalette.categorical()
    val growth by rememberChartGrowth()
    val total = genres.sumOf { it.count }.coerceAtLeast(1)

    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Canvas(Modifier.size(96.dp)) {
            var startAngle = -90f
            val strokeWidth = size.minDimension * 0.24f
            genres.forEachIndexed { index, genre ->
                val sweep = 360f * genre.count / total * growth
                drawArc(
                    color = genre.colorFor(index, palette),
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = Stroke(width = strokeWidth),
                )
                startAngle += sweep
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            genres.forEachIndexed { index, genre ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(10.dp).background(genre.colorFor(index, palette), CircleShape))
                    Text("${genre.genre} (${genre.count})", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun GenreCount.colorFor(index: Int, palette: List<Color>): Color = if (genre == "Other") OtherGenreColor else palette[index % palette.size]
