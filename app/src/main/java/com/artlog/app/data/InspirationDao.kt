package com.artlog.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data access for round 5 (Global Inspiration): favorites.
 *
 * Minimal v5 (Artlog 2.0): the offline feed cache table
 * (`inspiration_cache`) was dropped with MIGRATION_4_5, so this DAO
 * no longer has any cache methods — the feed fetches live only.
 *
 * Favorites live in their own table. Restoring a backup does not
 * copy cache rows back (see BackupManager), only favorites are
 * restored.
 */
@Dao
interface InspirationDao {

    // ---- Favorites ----

    @Query("SELECT * FROM inspiration_favorites ORDER BY savedAt DESC")
    fun observeFavorites(): Flow<List<InspirationFavorite>>

    @Query("SELECT id FROM inspiration_favorites")
    fun observeFavoriteIds(): Flow<List<String>>

    @Query("SELECT * FROM inspiration_favorites WHERE id = :id")
    suspend fun getFavorite(id: String): InspirationFavorite?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(favorite: InspirationFavorite)

    @Update
    suspend fun updateFavorite(favorite: InspirationFavorite)

    @Query("DELETE FROM inspiration_favorites WHERE id = :id")
    suspend fun deleteFavorite(id: String)

    /** Delete-all helper for round-4-style restore: only ever called
     * inside a Room transaction together with the other deletes, never alone. */
    @Query("DELETE FROM inspiration_favorites")
    suspend fun deleteAllFavorites()

    @Query("SELECT COUNT(*) FROM inspiration_favorites")
    suspend fun countFavorites(): Int

}
