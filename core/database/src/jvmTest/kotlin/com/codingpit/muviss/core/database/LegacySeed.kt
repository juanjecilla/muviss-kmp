package com.codingpit.muviss.core.database

import app.cash.sqldelight.db.SqlDriver

/**
 * Seeds a `collectionEntry` row into a database that is still at an OLD schema
 * version, using raw SQL that names only the columns which existed back then.
 *
 * The generated `collectionEntryQueries.upsert` cannot be used for this, and
 * the reason is the whole point of these tests. A migration test copies a
 * fixture at version N, writes data, and then migrates — but the generated
 * queries always speak the CURRENT schema. The moment any epic appends a
 * column to `collectionEntry`, every migration test that seeded through the
 * generated query starts failing with `table collectionEntry has no column
 * named <whatever was just added>`, in a test that has nothing to do with the
 * change. EPIC 41 is the second time this has bitten; before it, the table had
 * not gained a column since `1.sqm`, which is why it had never shown up.
 *
 * The column list below is deliberately frozen at the `1.sqm` era (everything
 * through `rating`/`note`, which is schema v2). Every fixture these tests load
 * is at v2 or later, so it is valid for all of them, and it will stay valid
 * however many columns later epics append — which is exactly the property the
 * generated query lacks.
 */
@Suppress("LongParameterList") // a row builder: one named default per column, so a test overrides only what it asserts on
internal fun SqlDriver.seedLegacyCollectionEntry(
    mediaId: String,
    mediaType: String = "tv",
    title: String = "Game of Thrones",
    posterUrl: String? = null,
    releaseYear: Long? = 2011,
    productionStatus: String = "ENDED",
    totalEpisodes: Long = 73,
    airedEpisodes: Long = 73,
    favorite: Boolean = true,
    genres: String = "Drama",
    runtimeMinutes: Long? = 57,
    addedAtEpochMs: Long = 500,
    updatedAtEpochMs: Long = 500,
    isDirty: Boolean = false,
    deleted: Boolean = false,
    notificationsMuted: Boolean = false,
    rating: Long? = null,
    note: String? = null,
) {
    execute(
        identifier = null,
        sql = """
            INSERT OR REPLACE INTO collectionEntry(
                mediaId, mediaType, title, posterUrl, releaseYear, productionStatus,
                totalEpisodes, airedEpisodes, favorite, genres, runtimeMinutes,
                addedAtEpochMs, updatedAtEpochMs, isDirty, deleted, notificationsMuted,
                rating, note
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        parameters = 18,
    ) {
        bindString(0, mediaId)
        bindString(1, mediaType)
        bindString(2, title)
        bindString(3, posterUrl)
        bindLong(4, releaseYear)
        bindString(5, productionStatus)
        bindLong(6, totalEpisodes)
        bindLong(7, airedEpisodes)
        bindLong(8, if (favorite) 1L else 0L)
        bindString(9, genres)
        bindLong(10, runtimeMinutes)
        bindLong(11, addedAtEpochMs)
        bindLong(12, updatedAtEpochMs)
        bindLong(13, if (isDirty) 1L else 0L)
        bindLong(14, if (deleted) 1L else 0L)
        bindLong(15, if (notificationsMuted) 1L else 0L)
        bindLong(16, rating)
        bindString(17, note)
    }
}
