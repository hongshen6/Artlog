package com.artlog.app.data

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Local backup / restore / integrity checking (round 4, Phase 4 data safety).
 *
 * Everything is local and offline: a backup is a ZIP file the user can see
 * in Documents/Artlog/backup/, containing the Room database file, every
 * artwork image file, and a manifest (manifest.json) that lists every file
 * with its size and SHA-256 checksum, plus the app version, database
 * version and manifest format version.
 *
 * Design rules (do not weaken them):
 * - A backup is never reported as successful unless the written ZIP was
 *   re-read and verified against its own manifest afterwards.
 * - A restore never touches live data before the staged backup has fully
 *   validated (manifest, versions, per-file checksums, database
 *   integrity_check, image rows ↔ image files). A snapshot of the current
 *   data is taken before the swap; if the swap fails, the snapshot is
 *   restored and the failure is reported. The live database file itself is
 *   never replaced — rows are swapped inside one Room transaction, so the
 *   open Room instance and all UI Flows keep working and update
 *   automatically.
 * - Nothing is ever deleted or "repaired" automatically by the integrity
 *   check; it only reports.
 */
class BackupManager(
    private val context: Context,
    private val database: ArtworkDatabase,
    private val dao: ArtworkDao,
    private val archiveDao: ArchiveDao,
    private val inspirationDao: InspirationDao,
    /** Minimal v5: website favorites (official + custom). */
    private val websiteFavoriteDao: WebsiteFavoriteDao,
    /** Database v6: website overrides (official-directory user overrides). */
    private val websiteOverrideDao: WebsiteOverrideDao,
    /** Database v6: directory categories (builtin + custom). */
    private val directoryCategoryDao: DirectoryCategoryDao,
    /** Database v6: music library metadata. */
    private val musicTrackDao: MusicTrackDao
) {

    companion object {
        private const val TAG = "BackupManager"

        /** Manifest format version. Bump only when manifest.json changes shape. */
        const val MANIFEST_FORMAT_VERSION = 1

        /** Database versions a backup may carry and still be restorable. */
        val SUPPORTED_DB_VERSIONS = setOf(1, 2, 3, 4, 5, 6, 7)

        /** Where exported backups live (user-visible, survives uninstall). */
        const val BACKUP_RELATIVE_PATH = "Documents/Artlog/backup"

        private const val DB_FILE_NAME = "artlog.db"
        private const val MANIFEST_NAME = "manifest.json"
        private const val DB_ZIP_PATH = "database/artlog.db"
        private const val IMAGES_ZIP_PREFIX = "images/"

        /** Database v6: music audio / LRC / background image ZIP prefixes. */
        private const val MUSIC_ZIP_PREFIX = "music/"
        private const val MUSIC_LYRICS_ZIP_PREFIX = "music/lyrics/"
        /** Database v7: album artwork cache ZIP prefix (`music/covers/`). */
        private const val MUSIC_COVERS_ZIP_PREFIX = "music/covers/"
        private const val BACKGROUND_ZIP_PREFIX = "background/"

        /** Settings file at the ZIP root (database v6). */
        private const val SETTINGS_JSON_NAME = "settings.json"

        /**
         * Appearance / lyrics preferences file (SharedPreferences).
         * The key names below must match `data/AppearanceSettings.kt`
         * (plan §2.5); this class reads/writes them directly so the
         * backup does not depend on the UI-side settings wrapper.
         */
        private const val APPEARANCE_PREFS_NAME = "artlog_appearance"

        /** Keys carried in settings.json (plan §2.5, exact names). */
        private val SETTINGS_KEYS = setOf(
            "background_mode", "background_preset", "background_custom_color",
            "background_image_path", "background_scrim_alpha", "lyrics_enabled",
            // P0 global lyrics: all new keys ride settings.json (same
            // whitelist contract as AppearanceSettings; keep in sync).
            "floating_lyrics_enabled", "external_lyrics_enabled",
            "lockscreen_lyrics_enabled", "floating_lyrics_locked",
            "floating_lyrics_x", "floating_lyrics_y",
            "floating_lyrics_font_size",
            // P0 acceptance-fix round: system overlay keys (independent
            // external_* keys, same whitelist contract).
            "external_lyrics_locked", "external_lyrics_x",
            "external_lyrics_y", "external_lyrics_font_size",
            // P1 playback mode: repeat mode (String) + shuffle switch
            // (Boolean); same whitelist contract as AppearanceSettings.
            "repeat_mode", "shuffle_enabled",
            // P2 home title: custom home title (String); same
            // whitelist contract as AppearanceSettings.
            "home_title",
            // P2 stage 5: global text size (Float) + text colour (Long)
            // + in-page lyrics font size (Float); same whitelist
            // contract as AppearanceSettings.
            "text_size_scale", "text_color", "lyrics_font_size",
            // Final round: album-cover one-off backfill flag (Boolean);
            // same whitelist contract as AppearanceSettings.
            "cover_backfill_done"
        )
    }

    // ---- Result types ----

    sealed interface BackupOutcome {
        data class Success(
            val fileName: String,
            val location: String,
            val artworkCount: Int,
            val imageCount: Int,
            /** Database v7 (交付复核后改为降级)：备份时跳过的缺失
             * 封面数。封面可从音频重新提取，缺失不让备份失败；
             * 该曲目的行仍保留 coverPath，恢复时按既有降级规则
             * 重写为 null（见恢复侧 coversDegraded）。 */
            val coversDegraded: Int = 0
        ) : BackupOutcome

        data class Failure(val message: String) : BackupOutcome
    }

    sealed interface RestoreOutcome {
        data class Success(
            val artworkCount: Int,
            val imageCount: Int,
            val tagCount: Int,
            val journalEntryCount: Int,
            /** Round 5: favorites restored (the feed cache is NOT restored — it is regenerable). */
            val favoriteCount: Int,
            /** Files present in the ZIP but not referenced by any DB row; ignored. */
            val skippedUnreferencedFiles: Int,
            /** Database v6: music tracks restored. */
            val musicTrackCount: Int = 0,
            /** Database v6: true when the backup's background image was
             * missing and the background was reset to the default solid
             * color instead of failing the restore. */
            val backgroundReset: Boolean = false,
            /** Database v6: non-null when settings.json could not be
             * parsed and default settings were applied instead. */
            val settingsWarning: String? = null,
            /** Database v7: how many restored tracks lost their cover
             * file in the backup and were degraded to "no cover"
             * instead of failing the restore. */
            val coversDegraded: Int = 0
        ) : RestoreOutcome

        data class Failure(val message: String) : RestoreOutcome
    }

    /** One file inside a backup, with its size and SHA-256 checksum. */
    data class BackupFileEntry(
        val path: String,
        val sizeBytes: Long,
        val sha256: String
    )

    /** The backup manifest (manifest.json inside the ZIP). */
    data class BackupManifest(
        val formatVersion: Int,
        val appVersionName: String,
        val appVersionCode: Long,
        val dbVersion: Int,
        val exportedAt: Long,
        val artworkCount: Int,
        val imageCount: Int,
        val tagCount: Int,
        val crossRefCount: Int,
        val journalEntryCount: Int,
        /** Round 5: favorited recommendations. Old (v10) backups lack this field. */
        val favoriteCount: Int,
        /**
         * 5.1: favorites split by content type (informational only —
         * restore validation still uses [favoriteCount] alone, so old
         * backups without these fields restore fine).
         */
        val favoriteImageCount: Int = 0,
        val favoriteLinkCount: Int = 0,
        /** Minimal v5: website favorites (official + custom). Old
         * (v4 and earlier) backups lack this field. */
        val websiteFavoriteCount: Int = 0,
        /** Database v6: website overrides. Old (v5 and earlier)
         * backups lack this field (counted as 0). */
        val websiteOverrideCount: Int = 0,
        /** Database v6: directory categories. Old backups lack this
         * field (counted as 0); a v5-or-earlier backup restores with
         * the 5 builtin seed rows. */
        val directoryCategoryCount: Int = 0,
        /** Database v6: music tracks. Old backups lack this field
         * (counted as 0). */
        val musicTrackCount: Int = 0,
        /** Database v7: tracks with a cover file (`coverPath != null`).
         * Old backups lack this field (counted as 0); a v6-or-earlier
         * backup has no covers, so 0 is the correct count there too. */
        val musicCoverCount: Int = 0,
        val files: List<BackupFileEntry>
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("formatVersion", formatVersion)
            put("appVersionName", appVersionName)
            put("appVersionCode", appVersionCode)
            put("dbVersion", dbVersion)
            put("exportedAt", exportedAt)
            put("artworkCount", artworkCount)
            put("imageCount", imageCount)
            put("tagCount", tagCount)
            put("crossRefCount", crossRefCount)
            put("journalEntryCount", journalEntryCount)
            put("favoriteCount", favoriteCount)
            put("favoriteImageCount", favoriteImageCount)
            put("favoriteLinkCount", favoriteLinkCount)
            put("websiteFavoriteCount", websiteFavoriteCount)
            put("websiteOverrideCount", websiteOverrideCount)
            put("directoryCategoryCount", directoryCategoryCount)
            put("musicTrackCount", musicTrackCount)
            put("musicCoverCount", musicCoverCount)
            put("files", JSONArray().apply {
                files.forEach { f ->
                    put(
                        JSONObject().apply {
                            put("path", f.path)
                            put("sizeBytes", f.sizeBytes)
                            put("sha256", f.sha256)
                        }
                    )
                }
            })
        }

        companion object {
            fun fromJson(text: String): BackupManifest {
                val json = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    throw IllegalArgumentException("备份清单损坏，无法解析")
                }
                fun requiredInt(key: String): Int {
                    if (!json.has(key)) throw IllegalArgumentException("备份清单缺少字段：$key")
                    return json.getInt(key)
                }
                val filesJson = if (!json.has("files")) {
                    throw IllegalArgumentException("备份清单缺少字段：files")
                } else {
                    json.getJSONArray("files")
                }
                val files = (0 until filesJson.length()).map { i ->
                    val o = filesJson.getJSONObject(i)
                    BackupFileEntry(
                        path = o.getString("path"),
                        sizeBytes = o.getLong("sizeBytes"),
                        sha256 = o.getString("sha256")
                    )
                }
                return BackupManifest(
                    formatVersion = requiredInt("formatVersion"),
                    appVersionName = json.optString("appVersionName", "未知"),
                    appVersionCode = json.optLong("appVersionCode", 0),
                    dbVersion = requiredInt("dbVersion"),
                    exportedAt = json.optLong("exportedAt", 0),
                    artworkCount = requiredInt("artworkCount"),
                    imageCount = requiredInt("imageCount"),
                    tagCount = requiredInt("tagCount"),
                    crossRefCount = requiredInt("crossRefCount"),
                    journalEntryCount = requiredInt("journalEntryCount"),
                    // Old (v10 and earlier) backups predate favorites.
                    favoriteCount = json.optInt("favoriteCount", 0),
                    // 5.1: old backups predate the IMAGE/LINK split.
                    favoriteImageCount = json.optInt("favoriteImageCount", 0),
                    favoriteLinkCount = json.optInt("favoriteLinkCount", 0),
                    // Minimal v5: old backups predate website favorites.
                    websiteFavoriteCount = json.optInt("websiteFavoriteCount", 0),
                    // Database v6: old backups predate overrides,
                    // categories and music tracks.
                    websiteOverrideCount = json.optInt("websiteOverrideCount", 0),
                    directoryCategoryCount = json.optInt("directoryCategoryCount", 0),
                    musicTrackCount = json.optInt("musicTrackCount", 0),
                    // Database v7: old backups predate covers.
                    musicCoverCount = json.optInt("musicCoverCount", 0),
                    files = files
                )
            }
        }
    }

    // ---- Integrity check ----

    enum class ProblemKind { MISSING, EMPTY, UNDECODABLE }

    data class IntegrityProblem(
        val artworkTitle: String,
        /** 原图 / 缩略图 / 封面 */
        val role: String,
        val fileName: String,
        val kind: ProblemKind
    )

    data class IntegrityReport(
        /** Image files referenced by the database and checked. */
        val checkedFiles: Int,
        val okFiles: Int,
        val problems: List<IntegrityProblem>,
        /** Files under Artlog/artworks/ not referenced by any DB row. */
        val orphanFiles: List<String>
    ) {
        val missingCount: Int get() = problems.count { it.kind == ProblemKind.MISSING }
        val emptyCount: Int get() = problems.count { it.kind == ProblemKind.EMPTY }
        val undecodableCount: Int get() = problems.count { it.kind == ProblemKind.UNDECODABLE }

        fun summary(): String = buildString {
            append("共检查 $checkedFiles 个图片文件，$okFiles 个正常")
            if (problems.isNotEmpty()) {
                append("；$missingCount 个缺失，$emptyCount 个空文件，$undecodableCount 个无法解码")
            }
            if (orphanFiles.isNotEmpty()) {
                append("；${orphanFiles.size} 个文件没有对应的数据库记录")
            }
            if (problems.isEmpty() && orphanFiles.isEmpty()) append("，全部正常")
        }
    }

    // ---- Backup ----

    /**
     * Create a backup ZIP in Documents/Artlog/backup/ and verify it by
     * re-reading it against its own manifest before reporting success.
     * Throws nothing: every failure is returned as [BackupOutcome.Failure].
     */
    suspend fun createBackup(): BackupOutcome = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "backup-staging")
        val zipFile = File(context.cacheDir, "artlog-backup-tmp.zip")
        try {
            staging.deleteRecursively()
            staging.mkdirs()
            zipFile.delete()

            // 1. Checkpoint WAL into the main DB file so the copied .db is
            //    self-consistent. Retries while a write is in flight; if it
            //    never settles, fail loudly instead of shipping a stale copy.
            checkpointWal()

            // 2. Read the live data (for counts) and copy the DB file.
            val artworks = dao.observeAll().first()
            val tags = archiveDao.observeTags().first()
            val crossRefs = archiveDao.observeAllCrossRefs().first()
            val journalEntries = archiveDao.observeJournalEntries().first()
            val favorites = inspirationDao.observeFavorites().first()
            val websiteFavorites = websiteFavoriteDao.observeFavorites().first()
            val websiteOverrides = websiteOverrideDao.observeOverrides().first()
            // Database v6: a fresh install that never opened the
            // directory has an empty categories table (Room creates
            // tables but does not run MIGRATION_5_6 seeds). Seed here
            // so the backup captures the true category set instead
            // of an empty one.
            if (directoryCategoryDao.countCategories() == 0) {
                DirectoryCategoryRepository.BUILTIN_SEEDS.forEach {
                    directoryCategoryDao.insertCategory(it)
                }
            }
            val directoryCategories = directoryCategoryDao.observeCategories().first()
            val musicTracks = musicTrackDao.observeTracks().first()
            val imageCount = artworks.sumOf { it.images.size }

            val dbFile = context.getDatabasePath(DB_FILE_NAME)
            if (!dbFile.exists()) throw IllegalStateException("找不到数据库文件")
            val stagedDb = File(staging, "database/$DB_FILE_NAME").apply { parentFile?.mkdirs() }
            dbFile.copyTo(stagedDb, overwrite = true)

            // 3. Copy every image file, one manifest record per file.
            //    A referenced file that is missing fails the backup — a
            //    backup that silently skips files is worse than no backup.
            val entries = mutableListOf<BackupFileEntry>()
            entries += fileEntry(DB_ZIP_PATH, stagedDb)
            val seenPaths = mutableSetOf<String>()
            for (item in artworks) {
                for (image in item.images) {
                    val original = File(image.imagePath)
                    val zipPath = imageZipPath(image.artworkId, "original", original.name)
                    if (seenPaths.add(image.imagePath)) {
                        if (!original.exists()) {
                            throw IllegalStateException(
                                "作品「${item.artwork.title}」的图片文件丢失，无法备份：" +
                                    original.name
                            )
                        }
                        val dest = File(staging, zipPath).apply { parentFile?.mkdirs() }
                        original.copyTo(dest, overwrite = true)
                        entries += fileEntry(zipPath, dest)
                    }
                    if (image.thumbnailPath != image.imagePath &&
                        seenPaths.add(image.thumbnailPath)
                    ) {
                        val thumb = File(image.thumbnailPath)
                        val thumbZipPath =
                            imageZipPath(image.artworkId, "thumbnails", thumb.name)
                        if (!thumb.exists()) {
                            throw IllegalStateException(
                                "作品「${item.artwork.title}」的缩略图文件丢失，无法备份：" +
                                    thumb.name
                            )
                        }
                        val dest = File(staging, thumbZipPath).apply { parentFile?.mkdirs() }
                        thumb.copyTo(dest, overwrite = true)
                        entries += fileEntry(thumbZipPath, dest)
                    }
                }
            }

            // 3b. Database v6: copy every music audio file and its LRC
            //     file (when the track has one). A referenced file that
            //     is missing fails the backup — same fail-fast rule as
            //     artwork images above.
            //     Database v7 (交付复核后改为降级)：封面缺失只计数、不失败
            //     ——封面是可再生的派生数据，见 3b2。
            var coversDegraded = 0
            for (track in musicTracks) {
                val audio = File(track.filePath)
                val audioZipPath = musicZipPath(track)
                if (!audio.exists()) {
                    throw IllegalStateException(
                        "音乐「${track.title}」的音频文件丢失，无法备份：" +
                            audio.name
                    )
                }
                val audioDest = File(staging, audioZipPath).apply { parentFile?.mkdirs() }
                audio.copyTo(audioDest, overwrite = true)
                entries += fileEntry(audioZipPath, audioDest)
                val lrcPath = track.lyricsFilePath
                if (lrcPath != null) {
                    val lrc = File(lrcPath)
                    val lrcZipPath = lyricsZipPath(track)
                    if (!lrc.exists()) {
                        throw IllegalStateException(
                            "音乐「${track.title}」的歌词文件丢失，无法备份：" +
                                lrc.name
                        )
                    }
                    val lrcDest = File(staging, lrcZipPath).apply { parentFile?.mkdirs() }
                    lrc.copyTo(lrcDest, overwrite = true)
                    entries += fileEntry(lrcZipPath, lrcDest)
                }
                // 3b2. Database v7: copy the album artwork cache file
                //      (when the track has one). A missing cover does
                //      NOT fail the backup (交付复核后由 fail-fast 改
                //      为降级)：封面是可从音频文件重新提取的派生数据，
                //      不是用户原始数据；因一张可再生的封面缺失而让
                //      整份备份失败，会让用户无法备份原始数据。缺失
                //      的封面跳过并计入 coversDegraded，备份继续；
                //      音频 / LRC / 作品图片缺失仍 fail-fast（原始数据）。
                //      manifest 的 musicCoverCount 仍按引用数写（不
                //      按实际拷入数）：恢复侧校验拿 staged 行数与该
                //      计数比对，缺失文件在恢复时由 degradedCoverTrackIds
                //      降级为无封面——若此处改写计数，恢复校验会误判。
                val coverPath = track.coverPath
                if (coverPath != null) {
                    val cover = File(coverPath)
                    val coverZipPath = coverZipPath(track)
                    if (AlbumCoverLogic.shouldDegradeMissingCover(coverPath, cover.exists())) {
                        coversDegraded++
                        Log.w(
                            TAG,
                            "backup: cover missing for track ${track.id}, " +
                                "skipping cover (degraded, backup continues)"
                        )
                    } else {
                        val coverDest = File(staging, coverZipPath).apply { parentFile?.mkdirs() }
                        cover.copyTo(coverDest, overwrite = true)
                        entries += fileEntry(coverZipPath, coverDest)
                    }
                }
            }

            // 3c. Database v6: background image + settings.json (ZIP
            //     root). Settings are read from SharedPreferences and
            //     written as settings.json; the background file is
            //     copied only when it still exists — a dangling path
            //     is dropped from the exported JSON instead of
            //     shipping a reference to a file that is already gone.
            val settingsJson = readAppearanceSettingsJson()
            val bgPath = settingsJson.optString("background_image_path", "")
                .takeIf { it.isNotBlank() }
            if (bgPath != null) {
                val bgFile = File(bgPath)
                if (bgFile.exists()) {
                    val bgZipPath = BACKGROUND_ZIP_PREFIX + bgFile.name
                    val bgDest = File(staging, bgZipPath).apply { parentFile?.mkdirs() }
                    bgFile.copyTo(bgDest, overwrite = true)
                    entries += fileEntry(bgZipPath, bgDest)
                } else {
                    settingsJson.remove("background_image_path")
                }
            }
            val settingsFile = File(staging, SETTINGS_JSON_NAME)
            settingsFile.writeText(settingsJson.toString(2))
            entries += fileEntry(SETTINGS_JSON_NAME, settingsFile)

            // 4. Manifest.
            val (appName, appCode) = appVersion()
            val manifest = BackupManifest(
                formatVersion = MANIFEST_FORMAT_VERSION,
                appVersionName = appName,
                appVersionCode = appCode,
                dbVersion = currentDbVersion(),
                exportedAt = System.currentTimeMillis(),
                artworkCount = artworks.size,
                imageCount = imageCount,
                tagCount = tags.size,
                crossRefCount = crossRefs.size,
                journalEntryCount = journalEntries.size,
                favoriteCount = favorites.size,
                // 5.1: informational split; restore still validates the total only.
                favoriteImageCount = favorites.count {
                    InspirationContentType.safeValueOf(it.contentType) ==
                        InspirationContentType.IMAGE
                },
                favoriteLinkCount = favorites.count {
                    InspirationContentType.safeValueOf(it.contentType) ==
                        InspirationContentType.LINK
                },
                // Minimal v5: website favorites are counted separately
                // from old work favorites and never mixed with them.
                websiteFavoriteCount = websiteFavorites.size,
                // Database v6: overrides, categories, music tracks.
                websiteOverrideCount = websiteOverrides.size,
                directoryCategoryCount = directoryCategories.size,
                musicTrackCount = musicTracks.size,
                // Database v7: tracks that carry a cover file.
                musicCoverCount = musicTracks.count { it.coverPath != null },
                files = entries
            )
            File(staging, MANIFEST_NAME).writeText(manifest.toJson().toString(2))

            // 5. Zip the staging directory.
            zipDirectory(staging, zipFile)

            // 6. Publish to Documents/Artlog/backup/ via MediaStore.
            val stamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val fileName = "artlog-backup-$stamp.zip"
            val uri = publishToDocuments(zipFile, fileName)
                ?: throw IllegalStateException("无法写入 Documents/Artlog/backup")

            // 7. Self-verify the published file against its own manifest.
            //    A mismatch means the backup on disk is not trustworthy:
            //    delete it and report failure instead of a fake success.
            try {
                verifyPublishedZip(uri, manifest)
            } catch (e: Exception) {
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (_: Exception) {
                }
                throw IllegalStateException("备份文件写入后校验失败，已删除：${e.message}")
            }

            BackupOutcome.Success(
                fileName = fileName,
                location = "文件 → 文档 → Artlog → backup",
                artworkCount = artworks.size,
                imageCount = imageCount,
                coversDegraded = coversDegraded
            )
        } catch (e: Exception) {
            Log.w(TAG, "backup failed", e)
            BackupOutcome.Failure("备份失败：${e.message ?: "未知错误"}")
        } finally {
            staging.deleteRecursively()
            zipFile.delete()
        }
    }

    // ---- Restore ----

    /**
     * Restore from a backup ZIP picked by the user. The ZIP is first copied
     * to a staging directory and fully validated; only then is a snapshot
     * of the current data taken and the swap performed. Any failure before
     * the swap leaves the current data untouched; a failure during the swap
     * triggers a rollback from the snapshot.
     */
    suspend fun restoreBackup(zipUri: Uri): RestoreOutcome =
        withContext(Dispatchers.IO) {
            val work = File(context.cacheDir, "restore")
            work.deleteRecursively()
            work.mkdirs()
            var swapBegan = false
            var rollbackOk = true
            try {
                // 1. Copy the picked file in and unzip to staging.
                val inputZip = File(work, "input.zip")
                context.contentResolver.openInputStream(zipUri)?.use { input ->
                    FileOutputStream(inputZip).use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("无法读取所选的备份文件")
                val staged = File(work, "staged").apply { mkdirs() }
                unzip(inputZip, staged)

                // 2. Manifest first: versions decide whether this backup
                //    may be restored at all.
                val manifestFile = File(staged, MANIFEST_NAME)
                if (!manifestFile.exists()) {
                    throw IllegalStateException("所选文件不是 Artlog 备份：缺少备份清单")
                }
                val manifest = BackupManifest.fromJson(manifestFile.readText())
                if (manifest.formatVersion != MANIFEST_FORMAT_VERSION) {
                    throw IllegalStateException(
                        "备份清单格式版本为 v${manifest.formatVersion}，" +
                            "当前 App 只支持 v$MANIFEST_FORMAT_VERSION，请更新 App 后再恢复"
                    )
                }
                if (manifest.dbVersion !in SUPPORTED_DB_VERSIONS) {
                    if (manifest.dbVersion > currentDbVersion()) {
                        throw IllegalStateException(
                            "该备份的数据库版本为 v${manifest.dbVersion}，" +
                                "高于当前 App 支持的 v${currentDbVersion()}，" +
                                "请先更新 App，不要盲目导入"
                        )
                    }
                    throw IllegalStateException(
                        "该备份的数据库版本为 v${manifest.dbVersion}，当前 App 不支持恢复"
                    )
                }

                // 3. Every file in the manifest must exist in the ZIP with
                //    matching size and SHA-256; no unlisted files allowed.
                val stagedFiles = staged.walkTopDown()
                    .filter { it.isFile }
                    .map { it.relativeTo(staged).path.replace(File.separator, "/") }
                    .toSet()
                for (entry in manifest.files) {
                    val file = File(staged, entry.path)
                    if (!file.exists()) {
                        throw IllegalStateException("备份不完整：缺少文件 ${entry.path}")
                    }
                    if (file.length() != entry.sizeBytes) {
                        throw IllegalStateException("备份损坏：文件 ${entry.path} 大小不符")
                    }
                    if (sha256(file) != entry.sha256) {
                        throw IllegalStateException("备份损坏：文件 ${entry.path} 校验值不符")
                    }
                }
                val listed = manifest.files.map { it.path }.toSet() + MANIFEST_NAME
                val unlisted = stagedFiles - listed
                if (unlisted.isNotEmpty()) {
                    throw IllegalStateException("备份包含未在清单中的文件：${unlisted.first()}")
                }

                // 4. Database: SQLite header, user_version matches the
                //    manifest, integrity_check must be "ok", required
                //    tables must exist.
                val stagedDb = File(staged, DB_ZIP_PATH)
                if (!stagedDb.exists()) {
                    throw IllegalStateException("备份不完整：缺少数据库文件")
                }
                validateDatabaseFile(stagedDb, manifest.dbVersion)

                // 5. Open the staged DB with a throwaway Room instance
                //    (v1 backups are migrated in place, on the staged
                //    copy only) and read every row. Counts must match the
                //    manifest, every journal link must point at an artwork
                //    that is in the backup, and every image row must have
                //    its files in the ZIP.
                val stagedData = readStagedDatabase(stagedDb)
                if (stagedData.artworks.size != manifest.artworkCount ||
                    stagedData.images.size != manifest.imageCount ||
                    stagedData.tags.size != manifest.tagCount ||
                    stagedData.crossRefs.size != manifest.crossRefCount ||
                    stagedData.journalEntries.size != manifest.journalEntryCount ||
                    stagedData.favorites.size != manifest.favoriteCount ||
                    stagedData.websiteFavorites.size != manifest.websiteFavoriteCount ||
                    stagedData.websiteOverrides.size != manifest.websiteOverrideCount ||
                    stagedData.musicTracks.size != manifest.musicTrackCount
                ) {
                    throw IllegalStateException(
                        "备份清单记录的数量与实际数据不符，" +
                            "可能是不完整的备份：作品 ${stagedData.artworks.size}/${manifest.artworkCount}，" +
                            "图片 ${stagedData.images.size}/${manifest.imageCount}，" +
                            "收藏 ${stagedData.favorites.size}/${manifest.favoriteCount}，" +
                            "网站收藏 ${stagedData.websiteFavorites.size}/${manifest.websiteFavoriteCount}，" +
                            "网站覆盖 ${stagedData.websiteOverrides.size}/${manifest.websiteOverrideCount}，" +
                            "音乐 ${stagedData.musicTracks.size}/${manifest.musicTrackCount}"
                    )
                }
                // Database v6: categories. A v6 backup must match its
                // manifest count; a v5-or-earlier backup has no count
                // (0) and restores with the 5 builtin seed rows that
                // MIGRATION_5_6 wrote into the staged copy.
                if (manifest.dbVersion >= 6) {
                    if (stagedData.directoryCategories.size != manifest.directoryCategoryCount) {
                        throw IllegalStateException(
                            "备份清单记录的数量与实际数据不符：分类 " +
                                "${stagedData.directoryCategories.size}/${manifest.directoryCategoryCount}"
                        )
                    }
                } else if (stagedData.directoryCategories.size !=
                    DirectoryCategoryRepository.BUILTIN_SEEDS.size
                ) {
                    throw IllegalStateException(
                        "旧备份恢复后分类种子不完整：" +
                            "应为 ${DirectoryCategoryRepository.BUILTIN_SEEDS.size} 个内置分类，" +
                            "实际 ${stagedData.directoryCategories.size} 个"
                    )
                }
                val artworkIds = stagedData.artworks.map { it.id }.toSet()
                for (entry in stagedData.journalEntries) {
                    val linked = entry.artworkId
                    if (linked != null && linked !in artworkIds) {
                        throw IllegalStateException("备份数据不一致：有手记关联了不存在的作品")
                    }
                }
                var skippedUnreferenced = 0
                val stagedImageFiles = staged
                    .walkTopDown()
                    .filter { it.isFile && it.relativeTo(staged).path.startsWith("images" + File.separator) }
                    .map { it.relativeTo(staged).path.replace(File.separator, "/") }
                    .toSet()
                val referencedZipPaths = mutableSetOf<String>()
                for (image in stagedData.images) {
                    val originalZip = imageZipPath(
                        image.artworkId, "original", File(image.imagePath).name
                    )
                    if (!File(staged, originalZip).exists()) {
                        val title = stagedData.artworks
                            .firstOrNull { it.id == image.artworkId }?.title ?: image.artworkId
                        throw IllegalStateException(
                            "备份不完整：作品「$title」的图片文件缺失"
                        )
                    }
                    referencedZipPaths += originalZip
                    if (image.thumbnailPath != image.imagePath) {
                        val thumbZip = imageZipPath(
                            image.artworkId, "thumbnails", File(image.thumbnailPath).name
                        )
                        if (!File(staged, thumbZip).exists()) {
                            val title = stagedData.artworks
                                .firstOrNull { it.id == image.artworkId }?.title ?: image.artworkId
                            throw IllegalStateException(
                                "备份不完整：作品「$title」的缩略图文件缺失"
                            )
                        }
                        referencedZipPaths += thumbZip
                    }
                }
                skippedUnreferenced = (stagedImageFiles - referencedZipPaths).size

                // 5b. Database v6: every music track's audio file must
                //     be in the ZIP (fail-fast, same rule as artwork
                //     images); a track with an LRC path must have its
                //     LRC file too. A missing background image does
                //     NOT fail the restore — it is handled in
                //     performSwap by resetting to the default solid
                //     background (a preference, not user content).
                for (track in stagedData.musicTracks) {
                    if (!File(staged, musicZipPath(track)).exists()) {
                        throw IllegalStateException(
                            "备份不完整：音乐「${track.title}」的音频文件缺失"
                        )
                    }
                    if (track.lyricsFilePath != null &&
                        !File(staged, lyricsZipPath(track)).exists()
                    ) {
                        throw IllegalStateException(
                            "备份不完整：音乐「${track.title}」的歌词文件缺失"
                        )
                    }
                }
                // Database v7: the manifest's cover count must match
                // the staged rows (a v6-or-earlier backup has 0/0).
                // A track whose cover file is missing from the ZIP
                // does NOT fail the restore: it is degraded to
                // "no cover" in performSwap (coverPath rewritten to
                // null) and counted in the outcome.
                val stagedCoverCount =
                    stagedData.musicTracks.count { it.coverPath != null }
                if (stagedCoverCount != manifest.musicCoverCount) {
                    throw IllegalStateException(
                        "备份清单记录的数量与实际数据不符：封面 " +
                            "$stagedCoverCount/${manifest.musicCoverCount}"
                    )
                }
                val degradedCoverTrackIds = stagedData.musicTracks
                    .filter { track ->
                        track.coverPath != null &&
                            !File(staged, coverZipPath(track)).exists()
                    }
                    .map { it.id }
                    .toSet()

                // 6. Snapshot the CURRENT data before touching anything.
                //    Both the rows (in memory, for a DB rollback) and the
                //    files (on disk, for a file rollback).
                val snapshot = takeSnapshot()

                // 7. Swap: new image tree first, then one Room transaction
                //    for all rows, then swap the directories.
                swapBegan = true
                val swapSettings: AppliedSettings
                try {
                    swapSettings = performSwap(staged, stagedData, degradedCoverTrackIds)
                } catch (e: Exception) {
                    rollbackOk = rollback(snapshot)
                    if (rollbackOk) {
                        throw IllegalStateException(
                            "替换失败，已还原原数据：${e.message ?: "未知错误"}"
                        )
                    } else {
                        throw IllegalStateException(
                            "替换失败，且回滚也失败，原数据可能已损坏，请立即联系开发者并保留 " +
                                "Documents/Artlog/backup 里的备份文件：${e.message ?: "未知错误"}"
                        )
                    }
                }

                RestoreOutcome.Success(
                    artworkCount = stagedData.artworks.size,
                    imageCount = stagedData.images.size,
                    tagCount = stagedData.tags.size,
                    journalEntryCount = stagedData.journalEntries.size,
                    favoriteCount = stagedData.favorites.size,
                    skippedUnreferencedFiles = skippedUnreferenced,
                    musicTrackCount = stagedData.musicTracks.size,
                    backgroundReset = swapSettings.backgroundReset,
                    settingsWarning = swapSettings.settingsWarning,
                    coversDegraded = swapSettings.coversDegraded
                )
            } catch (e: Exception) {
                Log.w(TAG, "restore failed (swapBegan=$swapBegan, rollbackOk=$rollbackOk)", e)
                val detail = e.message ?: "未知错误"
                // The inner swap catch already phrases the outcome
                // ("已还原原数据" / "回滚也失败"); the outer catch only adds
                // the "原数据未改动" guarantee for pre-swap failures.
                RestoreOutcome.Failure(
                    if (!swapBegan) "恢复失败，原数据未改动：$detail"
                    else "恢复失败：$detail"
                )
            } finally {
                work.deleteRecursively()
            }
        }

    // ---- Integrity check ----

    /**
     * Check every image file the database references (exists, non-empty,
     * decodable from its header only — the whole bitmap is never loaded)
     * and report files on disk that no DB row references, and vice versa.
     * Never deletes or repairs anything.
     */
    suspend fun checkIntegrity(): IntegrityReport = withContext(Dispatchers.IO) {
        val artworks = dao.observeAll().first()
        val problems = mutableListOf<IntegrityProblem>()
        val referenced = mutableSetOf<String>()
        var checked = 0
        var ok = 0

        fun checkFile(
            path: String,
            role: String,
            title: String,
            alreadyChecked: MutableSet<String>
        ) {
            if (!alreadyChecked.add(path)) return
            referenced += path
            checked++
            val file = File(path)
            when {
                !file.exists() -> problems += IntegrityProblem(
                    title, role, file.name, ProblemKind.MISSING
                )
                file.length() == 0L -> problems += IntegrityProblem(
                    title, role, file.name, ProblemKind.EMPTY
                )
                !decodable(file) -> problems += IntegrityProblem(
                    title, role, file.name, ProblemKind.UNDECODABLE
                )
                else -> ok++
            }
        }

        for (item in artworks) {
            val seenInArtwork = mutableSetOf<String>()
            for (image in item.images) {
                checkFile(image.imagePath, "原图", item.artwork.title, seenInArtwork)
                if (image.thumbnailPath != image.imagePath) {
                    checkFile(image.thumbnailPath, "缩略图", item.artwork.title, seenInArtwork)
                }
            }
            val cover = item.artwork.coverPath
            if (cover != null) {
                checkFile(cover, "封面", item.artwork.title, seenInArtwork)
            }
        }

        // Reverse direction: files on disk with no DB row.
        val artworksDir = File(context.filesDir, "Artlog/artworks")
        val onDisk = if (artworksDir.exists()) {
            artworksDir.walkTopDown().filter { it.isFile }
                .map { it.absolutePath }.toSet()
        } else {
            emptySet()
        }
        val orphans = (onDisk - referenced)
            .map { it.removePrefix(context.filesDir.absolutePath + "/") }
            .sorted()

        IntegrityReport(
            checkedFiles = checked,
            okFiles = ok,
            problems = problems,
            orphanFiles = orphans
        )
    }

    // ---- Internals: backup helpers ----

    private fun checkpointWal() {
        val db = database.openHelper.writableDatabase
        var attempt = 0
        while (attempt < 5) {
            val cursor = db.query("PRAGMA wal_checkpoint(TRUNCATE)")
            try {
                if (!cursor.moveToFirst()) return
                if (cursor.getInt(0) == 0) return
            } finally {
                cursor.close()
            }
            attempt++
            Thread.sleep(100)
        }
        throw IllegalStateException("数据库正在写入，无法安全备份，请稍后再试")
    }

    private fun currentDbVersion(): Int =
        database.openHelper.readableDatabase.version

    private fun appVersion(): Pair<String, Long> {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return (info.versionName ?: "未知") to PackageInfoCompat.getLongVersionCode(info)
    }

    private fun imageZipPath(artworkId: String, kind: String, fileName: String): String =
        "$IMAGES_ZIP_PREFIX$artworkId/$kind/$fileName"

    private fun fileEntry(zipPath: String, file: File): BackupFileEntry =
        BackupFileEntry(zipPath, file.length(), sha256(file))

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun zipDirectory(sourceDir: File, zipFile: File) {
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            sourceDir.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.relativeTo(sourceDir).path }
                .forEach { file ->
                    val entryPath = file.relativeTo(sourceDir).path
                        .replace(File.separator, "/")
                    zip.putNextEntry(ZipEntry(entryPath))
                    FileInputStream(file).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }

    /** Publish the finished ZIP to Documents/Artlog/backup/ via MediaStore. */
    private fun publishToDocuments(zipFile: File, fileName: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, BACKUP_RELATIVE_PATH)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Files.getContentUri("external"), values
        ) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { output ->
                FileInputStream(zipFile).use { input -> input.copyTo(output) }
            } ?: run {
                resolver.delete(uri, null, null)
                return null
            }
        } catch (e: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            throw e
        }
        return uri
    }

    /** Re-read a published ZIP and verify every file against the manifest. */
    private fun verifyPublishedZip(uri: Uri, manifest: BackupManifest) {
        val expected = manifest.files.associateBy { it.path }
        val seen = mutableSetOf<String>()
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val expectedEntry = expected[entry.name]
                            ?: throw IllegalStateException(
                                "备份文件包含未在清单中的条目：${entry.name}"
                            )
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        var total = 0L
                        while (zip.read(buffer).also { read = it } != -1) {
                            digest.update(buffer, 0, read)
                            total += read
                        }
                        val actual = digest.digest().joinToString("") { "%02x".format(it) }
                        if (total != expectedEntry.sizeBytes || actual != expectedEntry.sha256) {
                            throw IllegalStateException(
                                "备份文件校验失败：${entry.name}"
                            )
                        }
                        seen += entry.name
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: throw IllegalStateException("无法重新读取刚写入的备份文件")
        val missing = expected.keys - seen
        if (missing.isNotEmpty()) {
            throw IllegalStateException("备份文件缺少条目：${missing.first()}")
        }
    }

    // ---- Internals: restore helpers ----

    private fun unzip(zipFile: File, destDir: File) {
        ZipInputStream(FileInputStream(zipFile)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val dest = File(destDir, entry.name)
                    val canonicalDest = dest.canonicalFile
                    if (!canonicalDest.path.startsWith(destDir.canonicalFile.path)) {
                        throw IllegalStateException("备份包含非法路径：${entry.name}")
                    }
                    canonicalDest.parentFile?.mkdirs()
                    FileOutputStream(canonicalDest).use { output ->
                        zip.copyTo(output)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    /** SQLite header + user_version + integrity_check + required tables. */
    private fun validateDatabaseFile(dbFile: File, expectedVersion: Int) {
        val header = ByteArray(16)
        FileInputStream(dbFile).use { it.read(header) }
        val magic = String(header, Charsets.US_ASCII)
        if (!magic.startsWith("SQLite format 3")) {
            throw IllegalStateException("备份中的数据库文件不是有效的 SQLite 文件")
        }
        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
        )
        try {
            if (db.version != expectedVersion) {
                throw IllegalStateException(
                    "备份数据库的版本（v${db.version}）与清单记录（v$expectedVersion）不符"
                )
            }
            val integrity = db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            if (integrity != "ok") {
                throw IllegalStateException("备份数据库完整性检查未通过：$integrity")
            }
            val tables = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table'", null
            ).use { cursor ->
                val names = mutableSetOf<String>()
                while (cursor.moveToNext()) names += cursor.getString(0)
                names
            }
            val required = mutableListOf("artworks", "artwork_images")
            if (expectedVersion >= 2) {
                required += listOf("tags", "artwork_tag_cross_ref", "journal_entries")
            }
            if (expectedVersion >= 3) {
                required += "inspiration_favorites"
                // Minimal v5: inspiration_cache is DROPPED by
                // MIGRATION_4_5, so a v5 (or later) backup no longer
                // carries it; only v3/v4 backups are required to
                // have it. (The manifest never counted cache rows,
                // so dropping the table does not affect the manifest.)
                if (expectedVersion <= 4) required += "inspiration_cache"
            }
            // Stage 5: a v5 (or later) backup must carry
            // website_favorites. A v5 backup without it is rejected
            // here with an explicit "missing table" error instead of
            // failing later at the Room staging open. v<=4 backups
            // are NOT required to have it (the table did not exist
            // before v5; a v4 backup restores with zero website
            // favorites after MIGRATION_4_5 creates the empty table).
            if (expectedVersion >= 5) required += "website_favorites"
            // Database v6: a v6 (or later) backup must carry the three
            // new tables. A v5-or-earlier backup is NOT required to
            // have them (they did not exist before v6; the staged copy
            // gains them empty — categories with the 5 seed rows —
            // when readStagedDatabase runs MIGRATION_5_6).
            if (expectedVersion >= 6) {
                required += listOf(
                    "website_overrides", "directory_categories", "music_tracks"
                )
            }
            // Database v7: a v7 (or later) backup must carry the
            // coverPath column on music_tracks. A v6-or-earlier backup
            // is NOT required to have it (the staged copy gains the
            // nullable column, all NULL, when readStagedDatabase runs
            // MIGRATION_6_7).
            if (expectedVersion >= 7) {
                val trackColumns = db.rawQuery(
                    "PRAGMA table_info(`music_tracks`)", null
                ).use { cursor ->
                    val names = mutableSetOf<String>()
                    while (cursor.moveToNext()) names += cursor.getString(1)
                    names
                }
                if ("coverPath" !in trackColumns) {
                    throw IllegalStateException(
                        "备份数据库缺少 v7 字段：表 music_tracks 缺少 coverPath"
                    )
                }
            }
            val missing = required.filter { it !in tables }
            if (missing.isNotEmpty()) {
                throw IllegalStateException("备份数据库缺少表：${missing.joinToString("、")}")
            }
            // 5.1: v4 backups must carry the dual-channel columns, and
            // imageUrl must be nullable (LINK rows store null there).
            // A v4 database without them is not a valid 5.1 backup.
            // Minimal v5: inspiration_favorites keeps this check for
            // v4 AND v5 backups; inspiration_cache is only checked
            // for v4 backups (v5 backups no longer carry the table —
            // MIGRATION_4_5 drops it).
            if (expectedVersion >= 4) {
                val tablesToCheck = mutableListOf("inspiration_favorites")
                if (expectedVersion == 4) tablesToCheck += "inspiration_cache"
                for (table in tablesToCheck) {
                    val columns = db.rawQuery("PRAGMA table_info(`$table`)", null)
                        .use { cursor ->
                            val names = mutableSetOf<String>()
                            var imageUrlNullable: Boolean? = null
                            while (cursor.moveToNext()) {
                                names += cursor.getString(1)
                                if (cursor.getString(1) == "imageUrl") {
                                    // PRAGMA table_info: notnull == 1 means NOT NULL.
                                    imageUrlNullable = cursor.getInt(3) == 0
                                }
                            }
                            names to imageUrlNullable
                        }
                    val (names, imageUrlNullable) = columns
                    val missingCols = listOf("contentType", "licenseStatus")
                        .filter { it !in names }
                    if (missingCols.isNotEmpty()) {
                        throw IllegalStateException(
                            "备份数据库缺少 5.1 字段：表 $table 缺少 ${missingCols.joinToString("、")}"
                        )
                    }
                    if (imageUrlNullable != true) {
                        throw IllegalStateException(
                            "备份数据库的表 $table 的 imageUrl 列不是可空的，" +
                                "不是有效的 5.1（v4）备份"
                        )
                    }
                }
            }
        } finally {
            db.close()
        }
    }

    /** Rows read out of a staged backup database. */
    private data class StagedData(
        val artworks: List<Artwork>,
        val images: List<ArtworkImage>,
        val tags: List<Tag>,
        val crossRefs: List<ArtworkTagCrossRef>,
        val journalEntries: List<JournalEntry>,
        /** Round 5 favorites. The feed cache is NOT restored (regenerable). */
        val favorites: List<InspirationFavorite>,
        /** Minimal v5: website favorites (official + custom). */
        val websiteFavorites: List<WebsiteFavorite>,
        /** Database v6: website overrides. */
        val websiteOverrides: List<WebsiteOverride>,
        /** Database v6: directory categories. */
        val directoryCategories: List<DirectoryCategory>,
        /** Database v6: music tracks. */
        val musicTracks: List<MusicTrack>
    )

    /**
     * Read every row from the staged backup DB through a throwaway Room
     * instance. A v1 backup is migrated in place here — on the staged
     * copy only, never on the user's live data. A v2 backup likewise
     * runs MIGRATION_2_3 on the staged copy, so its favorites table
     * exists (empty) when the rows are read. A v3 backup runs
     * MIGRATION_3_4 on the staged copy, so its favorites are read with
     * their 5.1 contentType / licenseStatus (IMAGE / ALLOWED only
     * where the row's own license proves it). A v4 backup runs
     * MIGRATION_4_5 on the staged copy: the cache table is dropped
     * there (it is never read back) and `website_favorites` is
     * created empty, so a v4 backup restores with zero website
     * favorites and its old work favorites intact. A v5 backup runs
     * MIGRATION_5_6 on the staged copy: the three v6 tables are
     * created (overrides and tracks empty, categories with the 5
     * builtin seed rows), so a v5 backup restores with an empty
     * music library, the 5 builtin categories and no overrides.
     * A v6 backup runs MIGRATION_6_7 on the staged copy: `music_tracks`
     * gains the nullable `coverPath` column (all NULL), so a v6 backup
     * restores with no covers and the backfill flag unset (the backfill
     * runs once afterwards, see CoverBackfill).
     */
    private suspend fun readStagedDatabase(stagedDb: File): StagedData {
        val tempDb = Room.databaseBuilder(
            context, ArtworkDatabase::class.java, stagedDb.absolutePath
        ).addMigrations(
            ArtworkDatabase.MIGRATION_1_2, ArtworkDatabase.MIGRATION_2_3,
            ArtworkDatabase.MIGRATION_3_4, ArtworkDatabase.MIGRATION_4_5,
            ArtworkDatabase.MIGRATION_5_6, ArtworkDatabase.MIGRATION_6_7
        ).build()
        try {
            val artworks = tempDb.artworkDao().observeAll().first()
            return StagedData(
                artworks = artworks.map { it.artwork },
                images = artworks.flatMap { it.images },
                tags = tempDb.archiveDao().observeTags().first(),
                crossRefs = tempDb.archiveDao().observeAllCrossRefs().first(),
                journalEntries = tempDb.archiveDao().observeJournalEntries().first(),
                favorites = tempDb.inspirationDao().observeFavorites().first(),
                websiteFavorites = tempDb.websiteFavoriteDao().observeFavorites().first(),
                websiteOverrides = tempDb.websiteOverrideDao().observeOverrides().first(),
                directoryCategories = tempDb.directoryCategoryDao().observeCategories().first(),
                musicTracks = tempDb.musicTrackDao().observeTracks().first()
            )
        } finally {
            tempDb.close()
        }
    }

    /** What performSwap did with the backup's settings (for the outcome). */
    private data class AppliedSettings(
        val backgroundReset: Boolean,
        val settingsWarning: String?,
        /** Database v7: tracks degraded to "no cover" (cover file
         * missing from the backup). */
        val coversDegraded: Int = 0
    )

    /** In-memory + on-disk snapshot of the current data, for rollback. */
    private data class Snapshot(
        val artworks: List<Artwork>,
        val images: List<ArtworkImage>,
        val tags: List<Tag>,
        val crossRefs: List<ArtworkTagCrossRef>,
        val journalEntries: List<JournalEntry>,
        val favorites: List<InspirationFavorite>,
        /** Minimal v5: website favorites (official + custom). */
        val websiteFavorites: List<WebsiteFavorite>,
        /** Database v6: website overrides. */
        val websiteOverrides: List<WebsiteOverride>,
        /** Database v6: directory categories. */
        val directoryCategories: List<DirectoryCategory>,
        /** Database v6: music tracks (rows keep their live absolute paths). */
        val musicTracks: List<MusicTrack>,
        /** Copy of the current DB file (+ -wal/-shm) in the work dir. */
        val dbDir: File,
        /** Copy of the current Artlog/artworks dir, or null if none. */
        val artworksDir: File?,
        /** Copy of the current Artlog/music dir, or null if none. */
        val musicDir: File?,
        /** Copy of the current background image file, or null if none. */
        val backgroundFile: File?,
        /** Current appearance settings as JSON text (for prefs rollback). */
        val settingsJson: String
    )

    private suspend fun takeSnapshot(): Snapshot {
        val work = File(context.cacheDir, "restore")
        val snapDir = File(work, "snapshot").apply { mkdirs() }
        val dbDir = File(snapDir, "database").apply { mkdirs() }
        val liveDb = context.getDatabasePath(DB_FILE_NAME)
        liveDb.copyTo(File(dbDir, DB_FILE_NAME), overwrite = true)
        for (suffix in listOf("-wal", "-shm")) {
            val side = File(liveDb.absolutePath + suffix)
            if (side.exists()) side.copyTo(File(dbDir, DB_FILE_NAME + suffix), overwrite = true)
        }
        val liveArtworks = File(context.filesDir, "Artlog/artworks")
        val snapArtworks = if (liveArtworks.exists()) {
            File(snapDir, "artworks").apply {
                liveArtworks.copyRecursively(this, overwrite = true)
            }
        } else {
            null
        }
        val liveMusic = File(context.filesDir, "Artlog/music")
        val snapMusic = if (liveMusic.exists()) {
            File(snapDir, "music").apply {
                liveMusic.copyRecursively(this, overwrite = true)
            }
        } else {
            null
        }
        val currentBgPath = appearancePrefs()
            .getString("background_image_path", null)?.takeIf { it.isNotBlank() }
        val snapBackground = if (currentBgPath != null) {
            val bgFile = File(currentBgPath)
            if (bgFile.exists()) {
                File(snapDir, "background").apply { mkdirs() }
                    .resolve(bgFile.name).apply { bgFile.copyTo(this, overwrite = true) }
            } else {
                null
            }
        } else {
            null
        }
        val current = dao.observeAll().first()
        return Snapshot(
            artworks = current.map { it.artwork },
            images = current.flatMap { it.images },
            tags = archiveDao.observeTags().first(),
            crossRefs = archiveDao.observeAllCrossRefs().first(),
            journalEntries = archiveDao.observeJournalEntries().first(),
            favorites = inspirationDao.observeFavorites().first(),
            websiteFavorites = websiteFavoriteDao.observeFavorites().first(),
            websiteOverrides = websiteOverrideDao.observeOverrides().first(),
            directoryCategories = directoryCategoryDao.observeCategories().first(),
            musicTracks = musicTrackDao.observeTracks().first(),
            dbDir = dbDir,
            artworksDir = snapArtworks,
            musicDir = snapMusic,
            backgroundFile = snapBackground,
            settingsJson = readAppearanceSettingsJson().toString()
        )
    }

    /**
     * The actual swap. DB rows go first (one Room transaction), then the
     * image and music directories are swapped by rename, then the
     * backup's settings are applied. Any exception here is the caller's
     * signal to roll back from the snapshot.
     */
    private suspend fun performSwap(
        staged: File,
        data: StagedData,
        degradedCoverTrackIds: Set<String> = emptySet()
    ): AppliedSettings {
        val filesDir = context.filesDir
        val liveDir = File(filesDir, "Artlog/artworks")
        val newDir = File(filesDir, "Artlog/artworks.new")
        val oldDir = File(filesDir, "Artlog/artworks.old")
        newDir.deleteRecursively()
        oldDir.deleteRecursively()
        // Database v6: music tree, same rename pattern as artworks.
        val liveMusicDir = File(filesDir, "Artlog/music")
        val newMusicDir = File(filesDir, "Artlog/music.new")
        val oldMusicDir = File(filesDir, "Artlog/music.old")
        newMusicDir.deleteRecursively()
        oldMusicDir.deleteRecursively()

        // Stage the new image tree with rewritten (current-device) paths.
        val pathMap = mutableMapOf<String, String>()
        for (image in data.images) {
            val originalName = File(image.imagePath).name
            val stagedOriginal = File(
                staged, imageZipPath(image.artworkId, "original", originalName)
            )
            val destOriginal = File(
                File(File(newDir, image.artworkId), "original"), originalName
            ).apply { parentFile?.mkdirs() }
            stagedOriginal.copyTo(destOriginal, overwrite = true)
            pathMap[image.imagePath] = destOriginal.absolutePath
            if (image.thumbnailPath != image.imagePath) {
                val thumbName = File(image.thumbnailPath).name
                val stagedThumb = File(
                    staged, imageZipPath(image.artworkId, "thumbnails", thumbName)
                )
                val destThumb = File(
                    File(File(newDir, image.artworkId), "thumbnails"), thumbName
                ).apply { parentFile?.mkdirs() }
                stagedThumb.copyTo(destThumb, overwrite = true)
                pathMap[image.thumbnailPath] = destThumb.absolutePath
            } else {
                pathMap[image.thumbnailPath] = destOriginal.absolutePath
            }
        }
        fun rewrite(path: String?): String? = path?.let { pathMap[it] ?: it }

        // Database v6: stage the new music tree with rewritten
        // (current-device) paths, reusing the artwork pathMap pattern.
        // The LRC path is derived from the track id, so it is rewritten
        // in step with its track.
        val musicPathMap = mutableMapOf<String, String>()
        for (track in data.musicTracks) {
            val stagedAudio = File(staged, musicZipPath(track))
            val destAudio = File(newMusicDir, File(musicZipPath(track)).name)
                .apply { parentFile?.mkdirs() }
            stagedAudio.copyTo(destAudio, overwrite = true)
            musicPathMap[track.filePath] = destAudio.absolutePath
            if (track.lyricsFilePath != null) {
                val stagedLrc = File(staged, lyricsZipPath(track))
                val destLrc = File(
                    File(newMusicDir, "lyrics"), File(lyricsZipPath(track)).name
                ).apply { parentFile?.mkdirs() }
                stagedLrc.copyTo(destLrc, overwrite = true)
                musicPathMap[track.lyricsFilePath] = destLrc.absolutePath
            }
            // Database v7: stage the cover file the same way. A track
            // in degradedCoverTrackIds has no cover file in the ZIP:
            // its coverPath is rewritten to null below (degrade to
            // "no cover") instead of failing the restore.
            if (track.coverPath != null && track.id !in degradedCoverTrackIds) {
                val stagedCover = File(staged, coverZipPath(track))
                val destCover = File(
                    File(newMusicDir, "covers"), File(coverZipPath(track)).name
                ).apply { parentFile?.mkdirs() }
                stagedCover.copyTo(destCover, overwrite = true)
                musicPathMap[track.coverPath] = destCover.absolutePath
            }
        }
        fun rewriteMusic(path: String?): String? = path?.let { musicPathMap[it] ?: it }

        // One transaction for every row: delete everything, insert the
        // staged rows with rewritten paths. FK order: journal and links
        // first on delete; tags and artworks first on insert.
        // Favorites have no foreign keys; they are swapped in the same
        // transaction so a restore never leaves half a favorites list.
        // Website favorites likewise: no foreign keys, same
        // transaction, swapped as a whole (never mixed with the old
        // work favorites). Database v6 tables likewise: no foreign
        // keys, same transaction, swapped as a whole.
        database.withTransaction {
            archiveDao.deleteAllJournalEntries()
            archiveDao.deleteAllCrossRefs()
            dao.deleteAllArtworks() // images cascade
            archiveDao.deleteAllTags()
            inspirationDao.deleteAllFavorites()
            websiteFavoriteDao.deleteAllFavorites()
            websiteOverrideDao.deleteAllOverrides()
            directoryCategoryDao.deleteAllCategories()
            musicTrackDao.deleteAllTracks()
            data.tags.forEach { archiveDao.insertTag(it) }
            data.artworks.forEach { artwork ->
                dao.insertArtwork(artwork.copy(coverPath = rewrite(artwork.coverPath)))
            }
            dao.insertImages(
                data.images.map {
                    it.copy(
                        imagePath = rewrite(it.imagePath) ?: it.imagePath,
                        thumbnailPath = rewrite(it.thumbnailPath) ?: it.thumbnailPath
                    )
                }
            )
            data.crossRefs.forEach { archiveDao.insertCrossRef(it) }
            data.journalEntries.forEach { archiveDao.insertJournalEntry(it) }
            data.favorites.forEach { inspirationDao.insertFavorite(it) }
            data.websiteFavorites.forEach { websiteFavoriteDao.insertFavorite(it) }
            data.websiteOverrides.forEach { websiteOverrideDao.insertOverride(it) }
            data.directoryCategories.forEach { directoryCategoryDao.insertCategory(it) }
            data.musicTracks.forEach { track ->
                musicTrackDao.insertTrack(
                    track.copy(
                        filePath = rewriteMusic(track.filePath) ?: track.filePath,
                        lyricsFilePath = rewriteMusic(track.lyricsFilePath),
                        // Database v7: a degraded track's coverPath was
                        // never staged (no entry in musicPathMap), so
                        // rewrite to null instead of keeping the stale
                        // backup-device path.
                        coverPath = if (track.id in degradedCoverTrackIds) {
                            null
                        } else {
                            rewriteMusic(track.coverPath)
                        }
                    )
                )
            }
        }

        // Swap the image directories by rename (same filesystem).
        if (liveDir.exists()) {
            if (!liveDir.renameTo(oldDir)) {
                throw IllegalStateException("无法替换图片目录")
            }
        }
        if (!newDir.renameTo(liveDir)) {
            if (oldDir.exists()) oldDir.renameTo(liveDir)
            throw IllegalStateException("无法替换图片目录")
        }
        oldDir.deleteRecursively()

        // Database v6: swap the music directories by rename, same pattern.
        if (liveMusicDir.exists()) {
            if (!liveMusicDir.renameTo(oldMusicDir)) {
                throw IllegalStateException("无法替换音乐目录")
            }
        }
        if (data.musicTracks.isNotEmpty() || newMusicDir.exists()) {
            if (!newMusicDir.renameTo(liveMusicDir)) {
                if (oldMusicDir.exists()) oldMusicDir.renameTo(liveMusicDir)
                throw IllegalStateException("无法替换音乐目录")
            }
        }
        oldMusicDir.deleteRecursively()

        // Database v6: apply the backup's settings (last step — the
        // snapshot already holds the old settings for rollback).
        val applied = applyStagedSettings(staged)
        return applied.copy(coversDegraded = degradedCoverTrackIds.size)
    }

    /** Restore the snapshot taken before the swap. Returns false if the
     * rollback itself fails (the worst case; the caller must say so). */
    private suspend fun rollback(snapshot: Snapshot): Boolean {
        return try {
            val filesDir = context.filesDir
            val liveDir = File(filesDir, "Artlog/artworks")
            val newDir = File(filesDir, "Artlog/artworks.new")
            val oldDir = File(filesDir, "Artlog/artworks.old")
            newDir.deleteRecursively()
            oldDir.deleteRecursively()
            liveDir.deleteRecursively()
            snapshot.artworksDir?.copyRecursively(liveDir, overwrite = true)
            // Database v6: restore the music tree and the background
            // file the same way (the background dir holds at most one
            // file, so it is rebuilt from the snapshot).
            val liveMusicDir = File(filesDir, "Artlog/music")
            File(filesDir, "Artlog/music.new").deleteRecursively()
            File(filesDir, "Artlog/music.old").deleteRecursively()
            liveMusicDir.deleteRecursively()
            snapshot.musicDir?.copyRecursively(liveMusicDir, overwrite = true)
            val liveBackgroundDir = File(filesDir, "Artlog/background")
            liveBackgroundDir.deleteRecursively()
            snapshot.backgroundFile?.let { snapBg ->
                val dest = File(liveBackgroundDir, snapBg.name)
                    .apply { parentFile?.mkdirs() }
                snapBg.copyTo(dest, overwrite = true)
            }
            applySettingsJsonString(snapshot.settingsJson)
            database.withTransaction {
                archiveDao.deleteAllJournalEntries()
                archiveDao.deleteAllCrossRefs()
                dao.deleteAllArtworks()
                archiveDao.deleteAllTags()
                inspirationDao.deleteAllFavorites()
                websiteFavoriteDao.deleteAllFavorites()
                websiteOverrideDao.deleteAllOverrides()
                directoryCategoryDao.deleteAllCategories()
                musicTrackDao.deleteAllTracks()
                snapshot.tags.forEach { archiveDao.insertTag(it) }
                snapshot.artworks.forEach { dao.insertArtwork(it) }
                dao.insertImages(snapshot.images)
                snapshot.crossRefs.forEach { archiveDao.insertCrossRef(it) }
                snapshot.journalEntries.forEach { archiveDao.insertJournalEntry(it) }
                snapshot.favorites.forEach { inspirationDao.insertFavorite(it) }
                snapshot.websiteFavorites.forEach { websiteFavoriteDao.insertFavorite(it) }
                snapshot.websiteOverrides.forEach { websiteOverrideDao.insertOverride(it) }
                snapshot.directoryCategories.forEach { directoryCategoryDao.insertCategory(it) }
                snapshot.musicTracks.forEach { musicTrackDao.insertTrack(it) }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "rollback failed", e)
            false
        }
    }

    // ---- Internals: database-v6 settings / music helpers ----

    private fun appearancePrefs(): SharedPreferences =
        context.getSharedPreferences(APPEARANCE_PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Read the current appearance settings as JSON (only keys actually
     * present in SharedPreferences are written; absent keys are left
     * out so the restore side can apply its own defaults).
     */
    private fun readAppearanceSettingsJson(): JSONObject {
        val prefs = appearancePrefs()
        return JSONObject().apply {
            if (prefs.contains("background_mode")) {
                put("background_mode", prefs.getString("background_mode", null))
            }
            if (prefs.contains("background_preset")) {
                put("background_preset", prefs.getString("background_preset", null))
            }
            if (prefs.contains("background_custom_color")) {
                put("background_custom_color", prefs.getLong("background_custom_color", 0L))
            }
            if (prefs.contains("background_image_path")) {
                put("background_image_path", prefs.getString("background_image_path", null))
            }
            if (prefs.contains("background_scrim_alpha")) {
                put("background_scrim_alpha", prefs.getFloat("background_scrim_alpha", 0.45f).toDouble())
            }
            if (prefs.contains("lyrics_enabled")) {
                put("lyrics_enabled", prefs.getBoolean("lyrics_enabled", false))
            }
            if (prefs.contains("floating_lyrics_enabled")) {
                put("floating_lyrics_enabled", prefs.getBoolean("floating_lyrics_enabled", false))
            }
            if (prefs.contains("external_lyrics_enabled")) {
                put("external_lyrics_enabled", prefs.getBoolean("external_lyrics_enabled", false))
            }
            if (prefs.contains("lockscreen_lyrics_enabled")) {
                put("lockscreen_lyrics_enabled", prefs.getBoolean("lockscreen_lyrics_enabled", false))
            }
            if (prefs.contains("floating_lyrics_locked")) {
                put("floating_lyrics_locked", prefs.getBoolean("floating_lyrics_locked", false))
            }
            if (prefs.contains("floating_lyrics_x")) {
                put("floating_lyrics_x", prefs.getFloat("floating_lyrics_x", 0.5f).toDouble())
            }
            if (prefs.contains("floating_lyrics_y")) {
                put("floating_lyrics_y", prefs.getFloat("floating_lyrics_y", 0.78f).toDouble())
            }
            if (prefs.contains("floating_lyrics_font_size")) {
                put("floating_lyrics_font_size", prefs.getFloat("floating_lyrics_font_size", 16f).toDouble())
            }
            if (prefs.contains("external_lyrics_locked")) {
                put("external_lyrics_locked", prefs.getBoolean("external_lyrics_locked", false))
            }
            if (prefs.contains("external_lyrics_x")) {
                put("external_lyrics_x", prefs.getFloat("external_lyrics_x", 0.5f).toDouble())
            }
            if (prefs.contains("external_lyrics_y")) {
                put("external_lyrics_y", prefs.getFloat("external_lyrics_y", 0.78f).toDouble())
            }
            if (prefs.contains("external_lyrics_font_size")) {
                put("external_lyrics_font_size", prefs.getFloat("external_lyrics_font_size", 16f).toDouble())
            }
            if (prefs.contains("repeat_mode")) {
                put("repeat_mode", prefs.getString("repeat_mode", null))
            }
            if (prefs.contains("shuffle_enabled")) {
                put("shuffle_enabled", prefs.getBoolean("shuffle_enabled", false))
            }
            if (prefs.contains("home_title")) {
                put("home_title", prefs.getString("home_title", null))
            }
            if (prefs.contains("text_size_scale")) {
                put("text_size_scale", prefs.getFloat("text_size_scale", 1.0f).toDouble())
            }
            if (prefs.contains("text_color")) {
                put("text_color", prefs.getLong("text_color", 0L))
            }
            if (prefs.contains("lyrics_font_size")) {
                put("lyrics_font_size", prefs.getFloat("lyrics_font_size", 16f).toDouble())
            }
            if (prefs.contains("cover_backfill_done")) {
                put("cover_backfill_done", prefs.getBoolean("cover_backfill_done", false))
            }
        }
    }

    /**
     * Apply a settings JSON object to SharedPreferences: only known
     * keys are applied, unknown keys are ignored; a key missing from
     * the JSON is removed so the reader's default applies. Type
     * mismatches fall back to removing that key (default) instead of
     * crashing.
     */
    private fun applySettingsJson(json: JSONObject, backgroundImagePath: String?) {
        appearancePrefs().edit().apply {
            for (key in SETTINGS_KEYS) {
                if (!json.has(key) || json.isNull(key)) {
                    if (key == "background_image_path" && backgroundImagePath != null) {
                        putString(key, backgroundImagePath)
                    } else {
                        remove(key)
                    }
                    continue
                }
                try {
                    when (key) {
                        "background_mode" -> putString(key, json.getString(key))
                        "background_preset" -> putString(key, json.getString(key))
                        "background_custom_color" -> putLong(key, json.getLong(key))
                        "background_image_path" ->
                            putString(key, backgroundImagePath ?: json.getString(key))
                        "background_scrim_alpha" -> putFloat(key, json.getDouble(key).toFloat())
                        "lyrics_enabled" -> putBoolean(key, json.getBoolean(key))
                        "floating_lyrics_enabled" -> putBoolean(key, json.getBoolean(key))
                        "external_lyrics_enabled" -> putBoolean(key, json.getBoolean(key))
                        "lockscreen_lyrics_enabled" -> putBoolean(key, json.getBoolean(key))
                        "floating_lyrics_locked" -> putBoolean(key, json.getBoolean(key))
                        "floating_lyrics_x" -> putFloat(key, json.getDouble(key).toFloat())
                        "floating_lyrics_y" -> putFloat(key, json.getDouble(key).toFloat())
                        "floating_lyrics_font_size" -> putFloat(key, json.getDouble(key).toFloat())
                        "external_lyrics_locked" -> putBoolean(key, json.getBoolean(key))
                        "external_lyrics_x" -> putFloat(key, json.getDouble(key).toFloat())
                        "external_lyrics_y" -> putFloat(key, json.getDouble(key).toFloat())
                        "external_lyrics_font_size" -> putFloat(key, json.getDouble(key).toFloat())
                        "repeat_mode" -> putString(key, json.getString(key))
                        "shuffle_enabled" -> putBoolean(key, json.getBoolean(key))
                        "home_title" -> putString(key, json.getString(key))
                        "text_size_scale" -> putFloat(key, json.getDouble(key).toFloat())
                        "text_color" -> putLong(key, json.getLong(key))
                        "lyrics_font_size" -> putFloat(key, json.getDouble(key).toFloat())
                        "cover_backfill_done" -> putBoolean(key, json.getBoolean(key))
                    }
                } catch (_: Exception) {
                    remove(key)
                }
            }
            apply()
        }
    }

    /** Apply a settings JSON text (used by rollback from the snapshot). */
    private fun applySettingsJsonString(text: String) {
        try {
            applySettingsJson(JSONObject(text), null)
        } catch (_: Exception) {
            // Snapshot text is written by this class; a failure here
            // must not mask the rollback result.
            Log.w(TAG, "snapshot settings could not be re-applied")
        }
    }

    /**
     * Apply the staged backup's settings.json (last step of the swap).
     * No settings.json (v5-or-earlier backup) leaves the current
     * settings untouched. A background image is copied to the new
     * private directory first, then the stored path is rewritten to
     * the new absolute path; a missing background file does NOT fail
     * the restore — the background resets to the default solid color
     * (COLOR_PRESET, no image) and the caller reports it. A settings
     * file that cannot be parsed does not block the restore either:
     * defaults are applied and the caller reports a warning.
     */
    private fun applyStagedSettings(staged: File): AppliedSettings {
        val settingsFile = File(staged, SETTINGS_JSON_NAME)
        if (!settingsFile.exists()) {
            return AppliedSettings(backgroundReset = false, settingsWarning = null)
        }
        val json = try {
            JSONObject(settingsFile.readText())
        } catch (_: Exception) {
            clearAppearanceSettings()
            return AppliedSettings(
                backgroundReset = false,
                settingsWarning = "备份中的设置文件已损坏，已恢复为默认设置。"
            )
        }
        val oldBgPath = json.optString("background_image_path", "")
            .takeIf { it.isNotBlank() }
        var backgroundReset = false
        var newBgPath: String? = null
        if (oldBgPath != null) {
            val fileName = File(oldBgPath).name
            val stagedBg = File(staged, BACKGROUND_ZIP_PREFIX + fileName)
            if (stagedBg.exists()) {
                val dest = File(File(context.filesDir, "Artlog/background"), fileName)
                    .apply { parentFile?.mkdirs() }
                stagedBg.copyTo(dest, overwrite = true)
                newBgPath = dest.absolutePath
            } else {
                // Background image missing: reset to the default solid
                // color instead of failing the whole restore.
                backgroundReset = true
            }
        }
        if (backgroundReset) {
            // Force the default solid-color mode; the image path is
            // dropped. Other settings still apply from the JSON.
            json.put("background_mode", "COLOR_PRESET")
            json.remove("background_image_path")
        }
        applySettingsJson(json, newBgPath)
        return AppliedSettings(backgroundReset = backgroundReset, settingsWarning = null)
    }

    /** Remove all known appearance settings keys (defaults apply). */
    private fun clearAppearanceSettings() {
        appearancePrefs().edit().apply {
            for (key in SETTINGS_KEYS) remove(key)
            apply()
        }
    }

    /** ZIP path of a track's audio file: `music/<trackId>.<ext>`. */
    private fun musicZipPath(track: MusicTrack): String {
        val ext = track.fileName.substringAfterLast('.', "")
            .takeIf { it.isNotEmpty() }
            ?: track.filePath.substringAfterLast('.', "")
                .takeIf { it.isNotEmpty() }
        return MUSIC_ZIP_PREFIX + track.id + if (ext != null) ".$ext" else ""
    }

    /** ZIP path of a track's LRC file: `music/lyrics/<trackId>.lrc`. */
    private fun lyricsZipPath(track: MusicTrack): String =
        MUSIC_LYRICS_ZIP_PREFIX + track.id + ".lrc"

    /** ZIP path of a track's cover file: `music/covers/<coverFileName>`. */
    private fun coverZipPath(track: MusicTrack): String =
        MUSIC_COVERS_ZIP_PREFIX + File(track.coverPath ?: "").name

    /** Header-only decode: true when the file is a decodable image. */
    private fun decodable(file: File): Boolean {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            options.outWidth > 0 && options.outHeight > 0
        } catch (_: Exception) {
            false
        }
    }
}
