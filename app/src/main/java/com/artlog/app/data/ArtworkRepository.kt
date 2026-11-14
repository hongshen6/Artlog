package com.artlog.app.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID

/** Input for creating a new artwork. Mirrors the Add form. */
data class NewArtworkInput(
    val title: String,
    val createdAt: Long,
    val durationMinutes: Int?,
    val status: ArtworkStatus,
    val description: String,
    val intention: String,
    val reflection: String,
    val improvement: String,
    val imageUris: List<Uri>,
    val coverIndex: Int
)

/**
 * Repository (MVVM data layer): the single place that coordinates the
 * database and the file storage, so they can never drift apart.
 *
 * - Create: files are copied first, then the rows are inserted. If the
 *   database insert fails, the copied files are removed again.
 * - Delete: database rows are deleted first (images cascade), then the
 *   artwork's storage directory is removed.
 * - All heavy work runs on Dispatchers.IO.
 */
class ArtworkRepository(
    private val context: Context,
    private val dao: ArtworkDao,
    private val archiveDao: ArchiveDao
) {
    val allArtworks: Flow<List<ArtworkWithImages>> = dao.observeAll()

    fun artwork(id: String): Flow<ArtworkWithImages?> = dao.observeById(id)

    fun recentArtworks(limit: Int = 6): Flow<List<ArtworkWithImages>> =
        dao.observeRecent(limit)

    fun recentlyUpdated(limit: Int = 5): Flow<List<ArtworkWithImages>> =
        dao.observeRecentlyUpdated(limit)

    val artworkCount: Flow<Int> = dao.observeCount()

    val totalDurationMinutes: Flow<Int> = dao.observeTotalDurationMinutes()

    fun monthCount(monthStartMillis: Long): Flow<Int> =
        dao.observeMonthCount(monthStartMillis)

    /**
     * Create an artwork and return its generated ID.
     * Throws on failure; the caller keeps the filled-in form and shows
     * the error. No partial artwork is left behind.
     */
    suspend fun createArtwork(input: NewArtworkInput): String =
        withContext(Dispatchers.IO) {
            val artworkId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val imported = ImageStorage.copyImages(context, artworkId, input.imageUris)
            try {
                val coverPath = imported
                    .getOrNull(input.coverIndex.coerceIn(0, imported.lastIndex))
                    ?.imagePath
                val artwork = Artwork(
                    id = artworkId,
                    title = input.title,
                    coverPath = coverPath,
                    createdAt = input.createdAt,
                    addedAt = now,
                    updatedAt = now,
                    durationMinutes = input.durationMinutes,
                    status = input.status.name,
                    description = input.description,
                    intention = input.intention,
                    reflection = input.reflection,
                    improvement = input.improvement
                )
                dao.insertArtwork(artwork)
                dao.insertImages(
                    imported.mapIndexed { index, image ->
                        ArtworkImage(
                            id = UUID.randomUUID().toString(),
                            artworkId = artworkId,
                            imagePath = image.imagePath,
                            thumbnailPath = image.thumbnailPath,
                            stage = "ORIGINAL",
                            sortOrder = index,
                            createdAt = now,
                            note = ""
                        )
                    }
                )
            } catch (e: Exception) {
                // Roll back the copied files so a failed save leaves nothing.
                ImageStorage.deleteArtworkFiles(context, artworkId)
                throw e
            }
            artworkId
        }

    /** Update metadata/summary. The artwork's images are unchanged. */
    suspend fun updateArtwork(artwork: Artwork) = withContext(Dispatchers.IO) {
        dao.updateArtwork(artwork.copy(updatedAt = System.currentTimeMillis()))
    }

    /** Change the cover to one of the artwork's existing images. */
    suspend fun updateCover(artworkId: String, coverPath: String) =
        withContext(Dispatchers.IO) {
            val images = dao.getImages(artworkId)
            val target = images.firstOrNull { it.imagePath == coverPath }
                ?: throw IllegalArgumentException("该图片不属于此作品")
            dao.updateCover(artworkId, target.imagePath, System.currentTimeMillis())
        }

    /**
     * Delete an artwork: database rows first (images cascade), then the
     * storage directory. A file-cleanup failure is logged but does not
     * resurrect the deleted rows.
     */
    suspend fun deleteArtwork(id: String) = withContext(Dispatchers.IO) {
        dao.deleteArtwork(id)
        if (!ImageStorage.deleteArtworkFiles(context, id)) {
            Log.w("ArtworkRepository", "图片文件清理失败: $id")
        }
    }

    // ---- Tags (round 3) ----

    val tags: Flow<List<Tag>> = archiveDao.observeTags()

    fun tagsForArtwork(artworkId: String): Flow<List<Tag>> =
        archiveDao.observeTagsForArtwork(artworkId)

    fun artworkIdsForTag(tagId: String): Flow<List<String>> =
        archiveDao.observeArtworkIdsForTag(tagId)

    val allCrossRefs: Flow<List<ArtworkTagCrossRef>> =
        archiveDao.observeAllCrossRefs()

    /**
     * Create a tag. The name is trimmed; creating a tag whose name
     * already exists returns the existing tag instead of duplicating it.
     */
    suspend fun createTag(rawName: String): Tag = withContext(Dispatchers.IO) {
        val name = rawName.trim()
        require(name.isNotEmpty()) { "标签名不能为空" }
        archiveDao.getTagByName(name)?.let { return@withContext it }
        val tag = Tag(
            id = UUID.randomUUID().toString(),
            name = name,
            createdAt = System.currentTimeMillis()
        )
        archiveDao.insertTag(tag)
        tag
    }

    /** Rename a tag. Throws when the new name is blank or already taken. */
    suspend fun renameTag(tag: Tag, rawNewName: String) =
        withContext(Dispatchers.IO) {
            val newName = rawNewName.trim()
            require(newName.isNotEmpty()) { "标签名不能为空" }
            val existing = archiveDao.getTagByName(newName)
            if (existing != null && existing.id != tag.id) {
                throw IllegalArgumentException("已经有叫「$newName」的标签了")
            }
            archiveDao.updateTag(tag.copy(name = newName))
        }

    /** Delete a tag. Its cross-refs cascade; artworks are never deleted. */
    suspend fun deleteTag(tagId: String) = withContext(Dispatchers.IO) {
        archiveDao.deleteTag(tagId)
    }

    /** Replace an artwork's tags wholesale (Add/Edit and Detail). */
    suspend fun setArtworkTags(artworkId: String, tagIds: Set<String>) =
        withContext(Dispatchers.IO) {
            archiveDao.deleteCrossRefsForArtwork(artworkId)
            tagIds.forEach { tagId ->
                archiveDao.insertCrossRef(ArtworkTagCrossRef(artworkId, tagId))
            }
        }

    // ---- Journal (round 3) ----

    val journalEntries: Flow<List<JournalEntry>> =
        archiveDao.observeJournalEntries()

    fun journalByArtwork(artworkId: String): Flow<List<JournalEntry>> =
        archiveDao.observeJournalByArtwork(artworkId)

    /** Insert or replace a journal entry (same id = update). */
    suspend fun saveJournalEntry(entry: JournalEntry) =
        withContext(Dispatchers.IO) {
            archiveDao.insertJournalEntry(entry)
        }

    suspend fun deleteJournalEntry(id: String) = withContext(Dispatchers.IO) {
        archiveDao.deleteJournalEntry(id)
    }

    // ---- Statistics (round 3): SQL aggregates over real rows only ----

    fun monthlyCounts(): Flow<List<MonthCount>> =
        archiveDao.observeMonthlyCounts()

    fun statusCounts(): Flow<List<StatusCount>> =
        archiveDao.observeStatusCounts()

    fun yearlyCompletions(): Flow<List<YearCount>> =
        archiveDao.observeYearlyCompletions()

    fun dailyCounts(sinceMillis: Long): Flow<List<DayCount>> =
        archiveDao.observeDailyCounts(sinceMillis)
}
