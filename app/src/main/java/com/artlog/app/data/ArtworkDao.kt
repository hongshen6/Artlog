package com.artlog.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data access for artworks and their images.
 *
 * All list queries are Flows so the UI updates automatically when the
 * database changes. Statistics use SQL aggregates over real rows only:
 * SUM ignores NULL durations, so artworks without a filled-in duration
 * never contribute phantom minutes (spec section 3.6).
 */
@Dao
interface ArtworkDao {

    @Transaction
    @Query("SELECT * FROM artworks ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<ArtworkWithImages>>

    @Transaction
    @Query("SELECT * FROM artworks WHERE id = :id")
    fun observeById(id: String): Flow<ArtworkWithImages?>

    @Transaction
    @Query("SELECT * FROM artworks ORDER BY addedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ArtworkWithImages>>

    @Transaction
    @Query("SELECT * FROM artworks ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecentlyUpdated(limit: Int): Flow<List<ArtworkWithImages>>

    @Query("SELECT COUNT(*) FROM artworks")
    fun observeCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(durationMinutes), 0) FROM artworks")
    fun observeTotalDurationMinutes(): Flow<Int>

    @Query("SELECT COUNT(*) FROM artworks WHERE addedAt >= :monthStart")
    fun observeMonthCount(monthStart: Long): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArtwork(artwork: Artwork)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImages(images: List<ArtworkImage>)

    @Update
    suspend fun updateArtwork(artwork: Artwork)

    @Query("UPDATE artworks SET coverPath = :coverPath, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateCover(id: String, coverPath: String, updatedAt: Long)

    /** Images are removed by the CASCADE foreign key. */
    @Query("DELETE FROM artworks WHERE id = :id")
    suspend fun deleteArtwork(id: String)

    /** Delete every artwork (images cascade). Round 4 restore only:
     * always called inside a Room transaction together with the
     * ArchiveDao deletes, never on its own. */
    @Query("DELETE FROM artworks")
    suspend fun deleteAllArtworks()

    @Query("SELECT * FROM artwork_images WHERE artworkId = :artworkId ORDER BY sortOrder ASC")
    suspend fun getImages(artworkId: String): List<ArtworkImage>
}
