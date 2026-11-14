package com.artlog.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Read-only legacy-favorites repository (Artlog 2.0, stage 4).
 *
 * The old Feed is gone: `refreshFeedFlow`, `fetchCategoryUnit`,
 * `feedSources`, the circuit breaker, `FeedResult`, `normalizeWork`,
 * `WorkDedup` / `dedupeAndCap` / `mixByQuotas` and the favorite
 * write methods (`toggleFavorite`, `updateNote`, `deleteFavorite`,
 * `insertFavorite`) were all deleted with it. This class now owns
 * exactly one thing — the read path for the user's saved works.
 *
 * Read-only is structural, not a promise: there is no write method
 * left on this class to call. The only writes to
 * `inspiration_favorites` are the backup/restore row swap in
 * `BackupManager`, which goes through `InspirationDao` directly and
 * preserves rows exactly as stored (it deliberately does NOT gate).
 *
 * The [favorites] Flow is a READ path, so every row runs through
 * the display gate ([InspirationFavorite.displaySafe]) before the UI
 * sees it. A favorite stored by an older version (or a dirty row)
 * that no longer passes the gate is shown as LINK with its image
 * fields cleared — the row itself (and its note) is never dropped
 * from this Flow.
 */
class InspirationRepository(
    private val inspirationDao: InspirationDao,
) {
    /**
     * Saved works, gate-downgraded, newest first (DAO orders by
     * `savedAt DESC`). This is the only way the UI reads favorites —
     * never read `InspirationDao.observeFavorites()` directly, or
     * ungated image URLs would reach the UI.
     */
    val favorites: Flow<List<InspirationFavorite>> =
        inspirationDao.observeFavorites().map { list -> list.map { it.displaySafe() } }

    val favoriteIds: Flow<Set<String>> =
        inspirationDao.observeFavoriteIds().map { it.toSet() }

    suspend fun isFavorite(id: String): Boolean = withContext(Dispatchers.IO) {
        inspirationDao.getFavorite(id) != null
    }
}
