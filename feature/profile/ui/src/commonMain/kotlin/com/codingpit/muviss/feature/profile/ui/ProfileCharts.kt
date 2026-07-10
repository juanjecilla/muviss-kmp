package com.codingpit.muviss.feature.profile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.feature.profile.domain.GenreCount
import com.codingpit.muviss.feature.profile.domain.StatusBreakdown

// Compose-canvas-only bar/donut charts for the profile stats section (EPIC 4
// — no chart library allowed). Colors follow a fixed, ordered palette rather
// than being generated or reassigned by value, so the same status/genre
// always reads as the same color; both ramps were chosen to stay legible on
// light and dark surfaces (see isSystemInDarkTheme usage below).

/** Status is a funnel (not-started -> watching -> watched/finished), so an ordinal single-hue ramp reads better than four unrelated categorical hues. */
private val StatusRampLight = listOf(Color(0xFF86B6EF), Color(0xFF5598E7), Color(0xFF2A78D6), Color(0xFF1C5CAB))
private val StatusRampDark = listOf(Color(0xFF9EC5F4), Color(0xFF6DA7EC), Color(0xFF3987E5), Color(0xFF256ABF))

/** Genres are unordered identities, so a fixed-order categorical palette applies — a 9th+ genre folds into "Other" (see [foldGenresIntoOther]) rather than generating a new hue. */
private val GenreCategoricalLight =
    listOf(Color(0xFF2A78D6), Color(0xFF1BAF7A), Color(0xFFEDA100), Color(0xFF008300), Color(0xFF4A3AA7), Color(0xFFE34948), Color(0xFFE87BA4))
private val GenreCategoricalDark =
    listOf(Color(0xFF3987E5), Color(0xFF199E70), Color(0xFFC98500), Color(0xFF008300), Color(0xFF9085E9), Color(0xFFE66767), Color(0xFFD55181))
private val OtherGenreColor = Color(0xFF898781)
private const val MAX_GENRE_SLOTS = 7

/** Horizontal bars, one per [StatusBreakdown] bucket, each label + count always shown (never color-only identity). */
@Composable
fun StatusBarChart(breakdown: StatusBreakdown, modifier: Modifier = Modifier) {
    val ramp = if (isSystemInDarkTheme()) StatusRampDark else StatusRampLight
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
                Canvas(Modifier.weight(1f).height(14.dp)) {
                    val corner = CornerRadius(size.height / 2f, size.height / 2f)
                    drawRoundRect(color = ramp[index].copy(alpha = 0.18f), cornerRadius = corner)
                    if (fraction > 0f) {
                        drawRoundRect(
                            color = ramp[index],
                            size = size.copy(width = size.width * fraction),
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

/** Donut of [genres] (already folded via [foldGenresIntoOther]) with a color-keyed legend beside it. */
@Composable
fun GenreDonutChart(genres: List<GenreCount>, modifier: Modifier = Modifier) {
    if (genres.isEmpty()) return
    val palette = if (isSystemInDarkTheme()) GenreCategoricalDark else GenreCategoricalLight
    val total = genres.sumOf { it.count }.coerceAtLeast(1)

    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Canvas(Modifier.size(96.dp)) {
            var startAngle = -90f
            val strokeWidth = size.minDimension * 0.24f
            genres.forEachIndexed { index, genre ->
                val sweep = 360f * genre.count / total
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
