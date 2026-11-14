package com.artlog.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data access for round 3: tags, artwork–tag links, journal entries,
 * and the statistics aggregates.
 *
 * All statistics come from SQL aggregates over real rows only. Dates
 * are grouped with SQLite strftime over createdAt (epoch millis):
 * createdAt/1000 is unix seconds, 'unixepoch' + 'localtime' renders
 * the device-local date, matching how the app stores start-of-day
 * local timestamps.
 */
@Dao
interface ArchiveDao {

    // ---- Tags ----

    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun observeTags(): Flow<List<Tag>>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun getTag(id: String): Tag?

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun getTagByName(name: String): Tag?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTag(tag: Tag)

    @Update
    suspend fun updateTag(tag: Tag)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteTag(id: String)

    /** Delete-all helpers for round 4 restore: always called inside a
     * Room transaction together with the ArtworkDao deletes, never alone. */
    @Query("DELETE FROM tags")
    suspend fun deleteAllTags()

    @Query("DELETE FROM artwork_tag_cross_ref")
    suspend fun deleteAllCrossRefs()

    @Query("DELETE FROM journal_entries")
    suspend fun deleteAllJournalEntries()

    // ---- Artwork–tag links ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRef(ref: ArtworkTagCrossRef)

    @Query("DELETE FROM artwork_tag_cross_ref WHERE artworkId = :artworkId")
    suspend fun deleteCrossRefsForArtwork(artworkId: String)

    @Query("SELECT * FROM artwork_tag_cross_ref")
    fun observeAllCrossRefs(): Flow<List<ArtworkTagCrossRef>>

    @Query(
        "SELECT tags.* FROM tags " +
            "INNER JOIN artwork_tag_cross_ref " +
            "ON tags.id = artwork_tag_cross_ref.tagId " +
            "WHERE artwork_tag_cross_ref.artworkId = :artworkId " +
            "ORDER BY tags.name ASC"
    )
    fun observeTagsForArtwork(artworkId: String): Flow<List<Tag>>

    @Query("SELECT artworkId FROM artwork_tag_cross_ref WHERE tagId = :tagId")
    fun observeArtworkIdsForTag(tagId: String): Flow<List<String>>

    // ---- Journal ----

    @Query("SELECT * FROM journal_entries ORDER BY createdAt DESC")
    fun observeJournalEntries(): Flow<List<JournalEntry>>

    @Query("SELECT * FROM journal_entries WHERE artworkId = :artworkId ORDER BY createdAt DESC")
    fun observeJournalByArtwork(artworkId: String): Flow<List<JournalEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertJournalEntry(entry: JournalEntry)

    @Update
    suspend fun updateJournalEntry(entry: JournalEntry)

    @Query("DELETE FROM journal_entries WHERE id = :id")
    suspend fun deleteJournalEntry(id: String)

    // ---- Statistics (real rows only; SUM ignores NULL durations) ----

    @Query(
        "SELECT strftime('%Y-%m', createdAt/1000, 'unixepoch', 'localtime') AS month, " +
            "COUNT(*) AS count FROM artworks " +
            "GROUP BY strftime('%Y-%m', createdAt/1000, 'unixepoch', 'localtime') " +
            "ORDER BY month ASC"
    )
    fun observeMonthlyCounts(): Flow<List<MonthCount>>

    @Query("SELECT status, COUNT(*) AS count FROM artworks GROUP BY status")
    fun observeStatusCounts(): Flow<List<StatusCount>>

    @Query(
        "SELECT strftime('%Y', createdAt/1000, 'unixepoch', 'localtime') AS year, " +
            "COUNT(*) AS count FROM artworks WHERE status = 'COMPLETED' " +
            "GROUP BY strftime('%Y', createdAt/1000, 'unixepoch', 'localtime') " +
            "ORDER BY year DESC"
    )
    fun observeYearlyCompletions(): Flow<List<YearCount>>

    @Query(
        "SELECT strftime('%Y-%m-%d', createdAt/1000, 'unixepoch', 'localtime') AS day, " +
            "COUNT(*) AS count FROM artworks WHERE createdAt >= :sinceMillis " +
            "GROUP BY strftime('%Y-%m-%d', createdAt/1000, 'unixepoch', 'localtime')"
    )
    fun observeDailyCounts(sinceMillis: Long): Flow<List<DayCount>>
}
