package com.codingpit.muviss.feature.settings.domain

import com.codingpit.muviss.models.GenreKey

/**
 * One decision the person made about a genre (the `mutedGenre` table, ADR 0036). A live decision mutes the
 * genre whatever its default; a tombstone ([deleted]) unmutes it whatever its default.
 */
data class MutedGenreDecision(val key: GenreKey, val name: String, val deleted: Boolean)

/**
 * The effective Muted Genres set (ADR 0036). Pure, so the rule that decides what a device mutes is written
 * once and tested without a database.
 *
 * `muted(g) = decision(g)?.let { !deleted } ?: (g in defaults)`
 *
 * [defaults] is the default set in force when the person chose (the marker's snapshot), or the code's
 * defaults on a device that never chose. A decision overrides the default for its own genre only, so two
 * devices deciding different genres keep both, and a later change to the code's defaults does not reach a
 * device that chose.
 */
object MutedGenresRule {

    /**
     * The muted genres, with a name for each. A decision's name is the one the person saw when deciding; a
     * genre with no decision is named by its default.
     */
    fun effective(defaults: List<MutedGenreEntry>, decisions: List<MutedGenreDecision>): List<MutedGenreEntry> {
        val defaultsByKey = defaults.associateBy { it.key }
        val decidedByKey = decisions.associateBy { it.key }
        val keys = (defaults.map { it.key } + decisions.map { it.key }).distinct()
        return keys.mapNotNull { key ->
            val decision = decidedByKey[key]
            val muted = decision?.let { !it.deleted } ?: (key in defaultsByKey)
            if (!muted) {
                null
            } else {
                MutedGenreEntry(key, decision?.name ?: defaultsByKey.getValue(key).name)
            }
        }
    }
}
