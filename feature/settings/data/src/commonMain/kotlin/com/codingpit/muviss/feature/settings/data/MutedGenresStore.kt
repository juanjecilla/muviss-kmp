package com.codingpit.muviss.feature.settings.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.codingpit.muviss.core.database.MutedGenre
import com.codingpit.muviss.core.database.MutedGenreChoice
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.feature.settings.domain.MutedGenreDecision
import com.codingpit.muviss.feature.settings.domain.MutedGenreEntry
import com.codingpit.muviss.feature.settings.domain.MutedGenresCodec
import com.codingpit.muviss.feature.settings.domain.MutedGenresRule
import com.codingpit.muviss.models.GenreKey
import com.codingpit.muviss.models.MediaType

/**
 * The Muted Genres store (ADR 0036, #644): the choice marker, the decisions over its defaults, and the one-time
 * conversion from the legacy `appSettings.mutedGenres` string.
 *
 * The marker is what gates the conversion, so the marker and the rows it converts commit in one transaction. A
 * process that stopped after the marker alone would skip the conversion on the next open and lose the mutes the
 * string held. Parsing stays in [MutedGenresCodec]: genre names are free text, which a SQL split would mishandle.
 */
internal class MutedGenresStore(private val database: MuvissDatabase) {

    /** The effective set, as the person sees it, and as the export derives the legacy string from it. */
    suspend fun effective(): List<MutedGenreEntry> = effectiveOf(database.mutedGenreChoiceQueries.select().awaitAsOneOrNull(), database.mutedGenreQueries.selectAll().awaitAsList())

    /**
     * One explicit choice (ADR 0036): the marker if the person has not chosen before, with the defaults in force,
     * and the decision for this genre. Nothing else is written, least of all the defaults themselves.
     */
    suspend fun choose(key: GenreKey, name: String, muted: Boolean, nowEpochMs: Long) = database.transaction {
        if (!hasChosen()) {
            database.mutedGenreChoiceQueries.upsert(
                defaultsAtChoice = MutedGenresCodec.encode(MutedGenresCodec.DEFAULTS),
                updatedAtEpochMs = nowEpochMs,
                isDirty = true,
            )
        }
        database.mutedGenreQueries.upsert(
            mediaType = key.type.wireName,
            genreId = key.id.toLong(),
            name = name,
            deleted = !muted,
            isDirty = true,
            updatedAtEpochMs = nowEpochMs,
        )
    }

    /**
     * Converts a device's own legacy string, once: on the first open where there is no marker and the string is
     * not the default. A string equal to the default gets no marker, so the device keeps following the code's
     * defaults (ADR 0036).
     */
    suspend fun convertLegacy(legacy: String) {
        if (hasChosen()) return
        val entries = MutedGenresCodec.decode(legacy)
        if (sameKeys(entries, MutedGenresCodec.DEFAULTS)) return
        writeConverted(entries)
        // The string is dead from here. Pinned to its default so a later "Replace with <account>'s library" cannot
        // convert it a second time and bring back a list the person already moved on from.
        database.appSettingsQueries.updateMutedGenres(MutedGenresCodec.encode(MutedGenresCodec.DEFAULTS))
    }

    /**
     * A legacy backup's list, restored onto a device that has never chosen. The device's own string is converted
     * first, so its own list is never overwritten; a backup list equal to the defaults converts to nothing.
     */
    suspend fun restoreLegacy(backupList: List<MutedGenreEntry>, deviceLegacy: String) {
        convertLegacy(deviceLegacy)
        if (hasChosen() || sameKeys(backupList, MutedGenresCodec.DEFAULTS)) return
        writeConverted(backupList)
    }

    private suspend fun hasChosen(): Boolean = database.mutedGenreChoiceQueries.select().awaitAsOneOrNull() != null

    /** Writes the marker and the rows for [entries] at stamp `0`, dirty: older than any real change on the account (ADR 0036). */
    private suspend fun writeConverted(entries: List<MutedGenreEntry>) = database.transaction {
        database.mutedGenreChoiceQueries.upsert(
            defaultsAtChoice = MutedGenresCodec.encode(MutedGenresCodec.DEFAULTS),
            updatedAtEpochMs = 0L,
            isDirty = true,
        )
        val listed = entries.map { it.key }.toSet()
        entries.filter { entry -> MutedGenresCodec.DEFAULTS.none { it.key == entry.key } }.forEach { entry ->
            upsertDecision(entry.key, entry.name, deleted = false)
        }
        MutedGenresCodec.DEFAULTS.filter { it.key !in listed }.forEach { default ->
            upsertDecision(default.key, default.name, deleted = true)
        }
    }

    private suspend fun upsertDecision(key: GenreKey, name: String, deleted: Boolean) = database.mutedGenreQueries.upsert(
        mediaType = key.type.wireName,
        genreId = key.id.toLong(),
        name = name,
        deleted = deleted,
        isDirty = true,
        updatedAtEpochMs = 0L,
    )

    companion object {
        /** The effective set from a stored marker and its decisions, for the flows that hold both (the settings flow, the export). */
        fun effectiveOf(choice: MutedGenreChoice?, rows: List<MutedGenre>): List<MutedGenreEntry> = MutedGenresRule.effective(
            defaults = choice?.let { MutedGenresCodec.decode(it.defaultsAtChoice) } ?: MutedGenresCodec.DEFAULTS,
            decisions = rows.toDecisions(),
        )

        /** A row whose media type this build does not know is skipped rather than failing the list, as an unparsable entry is. */
        fun List<MutedGenre>.toDecisions(): List<MutedGenreDecision> = mapNotNull { row ->
            val type = MediaType.entries.firstOrNull { it.wireName == row.mediaType } ?: return@mapNotNull null
            MutedGenreDecision(GenreKey(type, row.genreId.toString()), row.name, deleted = row.deleted)
        }

        private fun sameKeys(entries: List<MutedGenreEntry>, other: List<MutedGenreEntry>): Boolean = entries.map { it.key }.toSet() == other.map { it.key }.toSet()
    }
}
