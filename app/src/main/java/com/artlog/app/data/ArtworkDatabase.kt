package com.artlog.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Local Room database (spec V1.0, section 4).
 *
 * Version 1 (round 2): artworks + artwork_images.
 * Version 2 (round 3): adds tags, the artwork–tag cross-ref table, and
 * journal_entries. The migration only creates the new tables — existing
 * artworks and images are untouched, no data is lost.
 *
 * Version 3 (round 5): adds inspiration_cache (offline feed cache) and
 * inspiration_favorites. Same rule: the migration only creates the new
 * tables; nothing existing is altered or lost.
 *
 * Version 4 (round 5.1): dual-channel IMAGE / LINK. Both inspiration
 * tables are REBUILT (new table → copy → drop → rename → re-index):
 * `imageUrl` becomes nullable and two columns are added
 * (`contentType`, `licenseStatus`). Old rows are classified per record
 * by their OWN `license` field — never by platform name: a row is
 * IMAGE / ALLOWED only when its license names a CC license or a
 * public-domain mark (see [isConfirmedDisplayLicense] in
 * ImageDisplayGate.kt; the same predicate is mirrored in SQL below);
 * every other row becomes LINK / UNKNOWN with its image fields
 * cleared, while sourceUrl, saved/fetched time and the user's note
 * are kept. Nothing else is altered; artworks, tags, journal entries
 * and image links are untouched.
 *
 * Version 5 (Artlog 2.0, minimal v5): adds the `website_favorites`
 * table (official-directory + custom website favorites, one row per
 * website) and DROPs `inspiration_cache` (the old feed cache: no
 * user data, regenerable). `inspiration_favorites` (old work
 * favorites, read-only history) is untouched, as are artworks,
 * tags, journal entries and image links. The migration only creates
 * the new table and drops the cache table; no existing row is
 * altered or lost.
 *
 * Version 6 (下一阶段迭代，最小 v6): adds three tables and nothing
 * else — `website_overrides` (user overrides of official directory
 * sites, including a nullable `descriptionOverride` so the 5 A-level
 * sites' blurbs are editable), `directory_categories` (builtin +
 * custom categories, with 5 builtin seed rows) and `music_tracks`
 * (local music library metadata). No existing table is altered, no
 * table is dropped, no existing row is touched.
 *
 * Version 7 (最终完善轮，最小 v7): adds the nullable `coverPath`
 * column to `music_tracks` (album artwork cache file path, one file
 * per track under `filesDir/Artlog/music/covers/`). Nothing else is
 * altered, no table is dropped, no existing row is touched: old rows
 * get `coverPath` NULL and keep showing the placeholder until the
 * one-off backfill (see CoverBackfill) or a re-import fills it.
 *
 * IMPORTANT for later rounds: never bump the version without adding a
 * [Migration] object. Do NOT use fallbackToDestructiveMigration —
 * destroying the user's archive on upgrade would violate the data-safety
 * requirements (spec section 8, Phase 4).
 */
@Database(
    entities = [
        Artwork::class,
        ArtworkImage::class,
        Tag::class,
        ArtworkTagCrossRef::class,
        JournalEntry::class,
        InspirationFavorite::class,
        WebsiteFavorite::class,
        WebsiteOverride::class,
        DirectoryCategory::class,
        MusicTrack::class,
    ],
    version = 7,
    exportSchema = false
)
abstract class ArtworkDatabase : RoomDatabase() {
    abstract fun artworkDao(): ArtworkDao
    abstract fun archiveDao(): ArchiveDao
    abstract fun inspirationDao(): InspirationDao
    abstract fun websiteFavoriteDao(): WebsiteFavoriteDao
    abstract fun websiteOverrideDao(): WebsiteOverrideDao
    abstract fun directoryCategoryDao(): DirectoryCategoryDao
    abstract fun musicTrackDao(): MusicTrackDao

    companion object {
        private const val DATABASE_NAME = "artlog.db"

        @Volatile
        private var instance: ArtworkDatabase? = null

        /**
         * Version 1 → 2: create the three round-3 tables. The SQL must
         * produce exactly the schema Room derives from the entities
         * (same columns, primary keys, foreign keys and index names);
         * it is checked against the Room-generated createAllTables SQL
         * after every build. No existing table is altered.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tags` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_name` " +
                        "ON `tags` (`name`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `artwork_tag_cross_ref` (" +
                        "`artworkId` TEXT NOT NULL, `tagId` TEXT NOT NULL, " +
                        "PRIMARY KEY(`artworkId`, `tagId`), " +
                        "FOREIGN KEY(`artworkId`) REFERENCES `artworks`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_artwork_tag_cross_ref_artworkId` " +
                        "ON `artwork_tag_cross_ref` (`artworkId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_artwork_tag_cross_ref_tagId` " +
                        "ON `artwork_tag_cross_ref` (`tagId`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `journal_entries` (" +
                        "`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, `artworkId` TEXT, " +
                        "PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`artworkId`) REFERENCES `artworks`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE SET NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_journal_entries_artworkId` " +
                        "ON `journal_entries` (`artworkId`)"
                )
            }
        }

        /**
         * Version 2 → 3: create the two round-5 tables (feed cache and
         * favorites). Same contract as MIGRATION_1_2: the SQL must
         * produce exactly the schema Room derives from the entities
         * (same columns, primary keys and index names); it is checked
         * against the Room-generated createAllTables SQL after every
         * build (manual-build/verify_migration.py). No existing table
         * is altered; existing data is untouched.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `inspiration_cache` (" +
                        "`id` TEXT NOT NULL, `category` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, `sourceId` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `author` TEXT, " +
                        "`sourceUrl` TEXT NOT NULL, `imageUrl` TEXT NOT NULL, " +
                        "`thumbnailUrl` TEXT, `width` INTEGER, `height` INTEGER, " +
                        "`description` TEXT, `license` TEXT, `licenseUrl` TEXT, " +
                        "`dateText` TEXT, `place` TEXT, " +
                        "`fetchedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`, `category`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_inspiration_cache_category` " +
                        "ON `inspiration_cache` (`category`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_inspiration_cache_fetchedAt` " +
                        "ON `inspiration_cache` (`fetchedAt`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `inspiration_favorites` (" +
                        "`id` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                        "`sourceId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`author` TEXT, `sourceUrl` TEXT NOT NULL, " +
                        "`imageUrl` TEXT NOT NULL, `thumbnailUrl` TEXT, " +
                        "`width` INTEGER, `height` INTEGER, " +
                        "`category` TEXT NOT NULL, `description` TEXT, " +
                        "`license` TEXT, `licenseUrl` TEXT, `dateText` TEXT, " +
                        "`place` TEXT, `savedAt` INTEGER NOT NULL, " +
                        "`note` TEXT NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_inspiration_favorites_savedAt` " +
                        "ON `inspiration_favorites` (`savedAt`)"
                )
            }
        }

        /**
         * Version 3 → 4 (round 5.1): rebuild both inspiration tables so
         * `imageUrl` becomes nullable and the two new columns
         * (`contentType`, `licenseStatus`) exist. The SQL here must be
         * plain string-literal concatenations inside each execSQL call:
         * manual-build/verify_migration.py parses the statements out of
         * this file, so no Kotlin variable may hold part of a statement.
         *
         * Classification (per record, by its OWN `license` field —
         * never by platform name, per the user's 2026-11-13 decision):
         * a row is IMAGE / ALLOWED only when its license names a CC
         * license or a public-domain mark. The predicate mirrors
         * [isConfirmedDisplayLicense] in ImageDisplayGate.kt; keep the two
         * in sync. Openverse stores CC license SLUGS ("BY-SA", "CC0",
         * "PDM", …), so the known slugs are listed explicitly — a
         * "%CC%" check alone would miss every "BY-*" Openverse record.
         * Every other row becomes LINK / UNKNOWN with its image fields
         * cleared; sourceUrl, time and the user's note are kept.
         *
         * The predicate is computed once per row (the `_ok` flag in the
         * subquery) instead of being repeated in every CASE, so the
         * INSERT stays a single statement.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // --- inspiration_favorites ---
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `inspiration_favorites_new` (" +
                        "`id` TEXT NOT NULL, `source` TEXT NOT NULL, `sourceId` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `author` TEXT, `sourceUrl` TEXT NOT NULL, " +
                        "`imageUrl` TEXT, `thumbnailUrl` TEXT, `width` INTEGER, `height` INTEGER, " +
                        "`category` TEXT NOT NULL, `description` TEXT, `license` TEXT, " +
                        "`licenseUrl` TEXT, `dateText` TEXT, `place` TEXT, " +
                        "`contentType` TEXT NOT NULL, `licenseStatus` TEXT NOT NULL, " +
                        "`savedAt` INTEGER NOT NULL, `note` TEXT NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT INTO `inspiration_favorites_new` (" +
                        "`id`, `source`, `sourceId`, `title`, `author`, `sourceUrl`, " +
                        "`imageUrl`, `thumbnailUrl`, `width`, `height`, `category`, " +
                        "`description`, `license`, `licenseUrl`, `dateText`, `place`, " +
                        "`contentType`, `licenseStatus`, `savedAt`, `note`) " +
                        "SELECT `id`, `source`, `sourceId`, `title`, `author`, `sourceUrl`, " +
                        "CASE WHEN `_ok` THEN `imageUrl` ELSE NULL END, " +
                        "CASE WHEN `_ok` THEN `thumbnailUrl` ELSE NULL END, " +
                        "CASE WHEN `_ok` THEN `width` ELSE NULL END, " +
                        "CASE WHEN `_ok` THEN `height` ELSE NULL END, " +
                        "`category`, `description`, `license`, `licenseUrl`, `dateText`, `place`, " +
                        "CASE WHEN `_ok` THEN 'IMAGE' ELSE 'LINK' END, " +
                        "CASE WHEN `_ok` THEN 'ALLOWED' ELSE 'UNKNOWN' END, " +
                        "`savedAt`, `note` FROM " +
                        "(SELECT *, (license IS NOT NULL AND TRIM(license) <> '' AND " +
                        "(UPPER(license) LIKE '%CC%' OR UPPER(license) LIKE '%PUBLIC DOMAIN%' OR " +
                        "UPPER(TRIM(license)) IN ('BY', 'BY-SA', 'BY-NC', 'BY-ND', 'BY-NC-SA', " +
                        "'BY-NC-ND', 'CC0', 'PDM', 'PUBLIC DOMAIN MARK', 'SAMPLING+', " +
                        "'NC-SAMPLING+'))) AS `_ok` FROM `inspiration_favorites`)"
                )
                db.execSQL("DROP TABLE `inspiration_favorites`")
                db.execSQL(
                    "ALTER TABLE `inspiration_favorites_new` RENAME TO `inspiration_favorites`"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_inspiration_favorites_savedAt` " +
                        "ON `inspiration_favorites` (`savedAt`)"
                )
                // --- inspiration_cache ---
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `inspiration_cache_new` (" +
                        "`id` TEXT NOT NULL, `category` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, `sourceId` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `author` TEXT, `sourceUrl` TEXT NOT NULL, " +
                        "`imageUrl` TEXT, `thumbnailUrl` TEXT, `width` INTEGER, `height` INTEGER, " +
                        "`description` TEXT, `license` TEXT, `licenseUrl` TEXT, " +
                        "`dateText` TEXT, `place` TEXT, " +
                        "`contentType` TEXT NOT NULL, `licenseStatus` TEXT NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`, `category`))"
                )
                db.execSQL(
                    "INSERT INTO `inspiration_cache_new` (" +
                        "`id`, `category`, `source`, `sourceId`, `title`, `author`, `sourceUrl`, " +
                        "`imageUrl`, `thumbnailUrl`, `width`, `height`, `description`, " +
                        "`license`, `licenseUrl`, `dateText`, `place`, " +
                        "`contentType`, `licenseStatus`, `fetchedAt`) " +
                        "SELECT `id`, `category`, `source`, `sourceId`, `title`, `author`, `sourceUrl`, " +
                        "CASE WHEN `_ok` THEN `imageUrl` ELSE NULL END, " +
                        "CASE WHEN `_ok` THEN `thumbnailUrl` ELSE NULL END, " +
                        "CASE WHEN `_ok` THEN `width` ELSE NULL END, " +
                        "CASE WHEN `_ok` THEN `height` ELSE NULL END, " +
                        "`description`, `license`, `licenseUrl`, `dateText`, `place`, " +
                        "CASE WHEN `_ok` THEN 'IMAGE' ELSE 'LINK' END, " +
                        "CASE WHEN `_ok` THEN 'ALLOWED' ELSE 'UNKNOWN' END, " +
                        "`fetchedAt` FROM " +
                        "(SELECT *, (license IS NOT NULL AND TRIM(license) <> '' AND " +
                        "(UPPER(license) LIKE '%CC%' OR UPPER(license) LIKE '%PUBLIC DOMAIN%' OR " +
                        "UPPER(TRIM(license)) IN ('BY', 'BY-SA', 'BY-NC', 'BY-ND', 'BY-NC-SA', " +
                        "'BY-NC-ND', 'CC0', 'PDM', 'PUBLIC DOMAIN MARK', 'SAMPLING+', " +
                        "'NC-SAMPLING+'))) AS `_ok` FROM `inspiration_cache`)"
                )
                db.execSQL("DROP TABLE `inspiration_cache`")
                db.execSQL(
                    "ALTER TABLE `inspiration_cache_new` RENAME TO `inspiration_cache`"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_inspiration_cache_category` " +
                        "ON `inspiration_cache` (`category`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_inspiration_cache_fetchedAt` " +
                        "ON `inspiration_cache` (`fetchedAt`)"
                )
            }
        }

        /**
         * Version 4 → 5 (Artlog 2.0, minimal v5): create the
         * `website_favorites` table and DROP `inspiration_cache`.
         * Same contract as MIGRATION_1_2: the SQL must produce exactly
         * the schema Room derives from the entity (same columns,
         * primary key and index name); it is checked against the
         * Room-generated createAllTables SQL after every build
         * (manual-build/verify_migration.py). No existing table is
         * altered and no existing row is touched: artworks, images,
         * tags, cross-refs, journal entries and the old
         * `inspiration_favorites` rows (including the user's notes)
         * survive verbatim; only the regenerable feed cache — which
         * holds no user data — is dropped. The CREATE TABLE below
         * must be plain string-literal concatenations inside each
         * execSQL call: manual-build/verify_migration.py parses the
         * statements out of this file, so no Kotlin variable may
         * hold part of a statement.
         *
         * Column note: `note` is `TEXT NOT NULL` with NO `DEFAULT`
         * clause — that is what Room generates for the entity's
         * `note: String = ""` (the Kotlin constructor default is not
         * a SQL default; the same holds for `inspiration_favorites`
         * in the generated Impl). Adding a DEFAULT here would make
         * the migrated schema differ from Room's expected schema
         * and Room would refuse to open the database.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `website_favorites` (" +
                        "`siteId` TEXT NOT NULL, `url` TEXT NOT NULL, `name` TEXT, " +
                        "`isCustom` INTEGER NOT NULL, `categories` TEXT, " +
                        "`note` TEXT NOT NULL, `savedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`siteId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_website_favorites_savedAt` " +
                        "ON `website_favorites` (`savedAt`)"
                )
                // The old feed cache: regenerable, no user data.
                // Dropping it also drops its two indexes.
                db.execSQL("DROP TABLE IF EXISTS `inspiration_cache`")
            }
        }

        /**
         * Version 5 → 6 (下一阶段迭代，最小 v6): create the three new
         * tables and seed the 5 builtin categories. Same contract as
         * MIGRATION_1_2: the SQL must produce exactly the schema Room
         * derives from the entities (same columns, primary keys and
         * index names); it is checked against the Room-generated
         * createAllTables SQL after every build
         * (manual-build/verify_migration.py). No existing table is
         * altered and no existing row is touched.
         *
         * The CREATE TABLE / CREATE INDEX / INSERT statements below
         * must be plain string-literal concatenations inside each
         * execSQL call: manual-build/verify_migration.py parses the
         * statements out of this file, so no Kotlin variable may
         * hold part of a statement.
         *
         * Seed rows: `INSERT OR IGNORE` (a re-run never duplicates);
         * sortOrder follows the existing visibleCategories order
         * (ANIME 0, REALISTIC 1, PHOTOGRAPHY 2, ARCHITECTURE 3,
         * LANDSCAPE 4); createdAt is a fixed seed timestamp, kept in
         * sync with DirectoryCategoryRepository.SEED_CREATED_AT.
         * Fresh installs do NOT run this migration — Room only
         * creates the tables — so the same 5 seeds are written by
         * DirectoryCategoryRepository.ensureSeeded() on first read.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `website_overrides` (" +
                        "`siteId` TEXT NOT NULL, `isDeleted` INTEGER NOT NULL, " +
                        "`nameOverride` TEXT, `descriptionOverride` TEXT, " +
                        "`urlOverride` TEXT, " +
                        "`categoriesOverride` TEXT, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`siteId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_website_overrides_updatedAt` " +
                        "ON `website_overrides` (`updatedAt`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `directory_categories` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`sortOrder` INTEGER NOT NULL, `isBuiltin` INTEGER NOT NULL, " +
                        "`isHidden` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_directory_categories_sortOrder` " +
                        "ON `directory_categories` (`sortOrder`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `music_tracks` (" +
                        "`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`artist` TEXT, `album` TEXT, `durationMs` INTEGER, " +
                        "`fileName` TEXT NOT NULL, `filePath` TEXT NOT NULL, " +
                        "`fileSizeBytes` INTEGER NOT NULL, `mimeType` TEXT, " +
                        "`lyricsText` TEXT, `lyricsSource` TEXT NOT NULL, " +
                        "`lyricsFilePath` TEXT, `importedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_music_tracks_importedAt` " +
                        "ON `music_tracks` (`importedAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_music_tracks_title` " +
                        "ON `music_tracks` (`title`)"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `directory_categories` " +
                        "(`id`, `name`, `sortOrder`, `isBuiltin`, `isHidden`, `createdAt`) " +
                        "VALUES ('ANIME', '二次元插画', 0, 1, 0, 1700000000000)"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `directory_categories` " +
                        "(`id`, `name`, `sortOrder`, `isBuiltin`, `isHidden`, `createdAt`) " +
                        "VALUES ('REALISTIC', '写实插画', 1, 1, 0, 1700000000000)"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `directory_categories` " +
                        "(`id`, `name`, `sortOrder`, `isBuiltin`, `isHidden`, `createdAt`) " +
                        "VALUES ('PHOTOGRAPHY', '情绪摄影', 2, 1, 0, 1700000000000)"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `directory_categories` " +
                        "(`id`, `name`, `sortOrder`, `isBuiltin`, `isHidden`, `createdAt`) " +
                        "VALUES ('ARCHITECTURE', '建筑美学', 3, 1, 0, 1700000000000)"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `directory_categories` " +
                        "(`id`, `name`, `sortOrder`, `isBuiltin`, `isHidden`, `createdAt`) " +
                        "VALUES ('LANDSCAPE', '风景与氛围', 4, 1, 0, 1700000000000)"
                )
            }
        }

        /**
         * Version 6 → 7 (最终完善轮，最小 v7): add the nullable
         * `coverPath` column to `music_tracks` (album artwork cache
         * file path). Same contract as MIGRATION_1_2: the SQL must
         * produce exactly the schema Room derives from the entity;
         * it is checked against the Room-generated createAllTables
         * SQL after every build (manual-build/verify_migration.py).
         * No existing row is altered: the new column is nullable
         * with no DEFAULT, so every old row reads NULL after the
         * upgrade and keeps showing the placeholder until the
         * one-off backfill fills it (see CoverBackfill).
         *
         * The statement below must be plain string literals inside
         * the execSQL call: manual-build/verify_migration.py parses
         * the statements out of this file, so no Kotlin variable may
         * hold part of a statement. `coverPath` is the LAST column
         * of the MusicTrack entity, so the appended column lands in
         * the same position Room's v7 CREATE TABLE puts it.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `music_tracks` ADD COLUMN `coverPath` TEXT"
                )
            }
        }

        fun get(context: Context): ArtworkDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ArtworkDatabase::class.java,
                    DATABASE_NAME
                ).addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                    MIGRATION_5_6, MIGRATION_6_7
                ).build().also { instance = it }
            }
        }
    }
}
