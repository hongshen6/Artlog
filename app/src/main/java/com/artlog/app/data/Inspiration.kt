package com.artlog.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Post-feed data model (Artlog 2.0, stage 4: the old Feed is removed).
 *
 * What used to live here and is gone with the Feed:
 * - the live sources (Openverse / Commons / Met / AIC clients,
 *   `InspirationSources.kt`), the circuit breaker, the relevance
 *   rules, the quota mix (`InspirationMixConfig`), the Commons
 *   search-mode enum and every per-category query function;
 * - the regenerable offline cache entity (`InspirationCacheItem`,
 *   table dropped with MIGRATION_4_5 in stage 1).
 *
 * What remains, and why:
 * - [InspirationWork]: a bare data class with no network, paging or
 *   mixing logic of its own. It is kept because the retained
 *   [ImageDisplayGate] still defines its Work overloads
 *   (`displaySafe` / `effectiveContentType` / `displayImageUrl`) and
 *   [InspirationFavorite.toWork] / `fromWork` convert through it —
 *   deleting it would have forced deleting those two retained
 *   pieces as well. Its only live use is the gate (and its JVM
 *   tests); no production code fetches or builds works any more.
 * - [InspirationFavorite]: the user's own saved works (metadata +
 *   links only, never a local image copy), read-only since stage 3.
 *   Its `note` is user-written only.
 * - [InspirationCategory]: the five directory categories, reused by
 *   the new visual-inspiration directory (`VisualDirectory`).
 * - [InspirationContentType] / [InspirationLicenseStatus] /
 *   [InspirationSource]: the small enums the gate depends on.
 *
 * Source values kept: [InspirationSource.OPENVERSE] and
 * [InspirationSource.COMMONS] are the stored source strings of
 * existing user favorites and the only values that let the retained
 * gate's IMAGE path function (the gate requires
 * `source != UNKNOWN`); deleting them would silently downgrade every
 * existing favorite to LINK and leave the gate's positive path
 * unreachable and untestable. MET / AIC and the 5.1 LINK-candidate
 * values (GRACG / ZCOOL / LOFTER / MIHUASHI / TUCHONG / HELLORF /
 * GOOOOD / ARCHIPOSITION / ARCHDAILY / ARCHCOLLEGE) are deleted:
 * Met/AIC favorites, if any exist, now parse to UNKNOWN and show as
 * LINK (safe direction — text + original link, never an image).
 */

/** Where a saved work comes from. Only UNKNOWN plus the two sources
 * whose favorites can still exist in user data. */
enum class InspirationSource(val label: String) {
    OPENVERSE("Openverse"),
    COMMONS("Wikimedia Commons"),
    /** Fallback for a stored source string no known value matches. */
    UNKNOWN("未知来源");

    companion object {
        /**
         * Parse a stored source string without crashing. Unknown values
         * land in UNKNOWN instead of throwing (the old code used
         * valueOf() directly, which crashed on unknown strings).
         * UNKNOWN works are always treated as LINK by the gate.
         */
        fun safeValueOf(name: String?): InspirationSource = try {
            valueOf(name ?: "")
        } catch (_: Exception) {
            UNKNOWN
        }
    }
}

/** Content type: IMAGE is shown inside the app; LINK shows text + the original page link only. */
enum class InspirationContentType {
    IMAGE, LINK;

    companion object {
        /**
         * Parse a stored content-type string without crashing.
         * Unknown values land in LINK — never display an image on an
         * unrecognized type.
         */
        fun safeValueOf(name: String?): InspirationContentType = when (name) {
            "IMAGE" -> IMAGE
            "LINK" -> LINK
            else -> LINK
        }
    }
}

/**
 * Display-authorization status of one work. Only [ALLOWED] permits
 * in-app image display, and only together with contentType IMAGE.
 * [UNKNOWN] is the default for anything not positively confirmed.
 */
enum class InspirationLicenseStatus(val label: String) {
    ALLOWED("已确认展示授权"),
    NOT_ALLOWED("已确认不允许展示"),
    UNKNOWN("尚未确认"),
    LINK_ONLY("仅允许链接跳转");

    companion object {
        /** Parse a stored status string without crashing; unknown → UNKNOWN. */
        fun safeValueOf(name: String?): InspirationLicenseStatus = when (name) {
            "ALLOWED" -> ALLOWED
            "NOT_ALLOWED" -> NOT_ALLOWED
            "UNKNOWN" -> UNKNOWN
            "LINK_ONLY" -> LINK_ONLY
            else -> UNKNOWN
        }
    }
}

/**
 * One saved / recommended work, in source-agnostic form. Everything
 * except [id], [source], [title] and [sourceUrl] may be absent.
 *
 * [imageUrl] is nullable: a LINK work has null image fields by
 * construction and must never be passed to an image loader.
 * [contentType] defaults to IMAGE but [licenseStatus] defaults to
 * UNKNOWN, and the gate downgrades any IMAGE whose status is not
 * ALLOWED — so the defaults can never authorize an image by accident.
 *
 * No fetching, paging, mixing or dedupe logic lives on this class
 * any more (stage 4 removed the Feed); it is a plain record the
 * gate and the favorite conversions operate on.
 */
data class InspirationWork(
    /** Stable key: "<source>:<sourceId>", e.g. "commons:436535". */
    val id: String,
    val source: InspirationSource,
    val sourceId: String,
    val title: String,
    val author: String?,
    /** Landing page of the work. */
    val sourceUrl: String,
    /** Direct image URL (hotlinked, per each source's display rules). Null for LINK works. */
    val imageUrl: String?,
    /** Smaller image URL for the grid, when the source provides one. Null for LINK works. */
    val thumbnailUrl: String?,
    val width: Int?,
    val height: Int?,
    val category: InspirationCategory,
    /** Source-provided description (medium / date / notes), if any. */
    val description: String?,
    /** License short name as reported by the source, e.g. "CC BY 2.0", "CC0". */
    val license: String?,
    val licenseUrl: String?,
    /** Source-provided date text (object date / creation date), if any. */
    val dateText: String?,
    /** Source-provided place / culture / origin, if any. */
    val place: String?,
    /** IMAGE = shown in-app; LINK = text + original link only. */
    val contentType: InspirationContentType = InspirationContentType.IMAGE,
    /** Display authorization. Only ALLOWED permits image display. */
    val licenseStatus: InspirationLicenseStatus = InspirationLicenseStatus.UNKNOWN,
    /**
     * When this work was fetched. Favorites leave it null and use
     * [InspirationFavorite.savedAt] instead.
     */
    val fetchedAt: Long? = null,
)

/**
 * Directory categories. The five subject categories match the new
 * visual-inspiration directory; [ALL] mixes them in the directory
 * UI and [OTHER] is a fallback bucket for stored values that cannot
 * be classified confidently (never shown as a chip).
 *
 * Favorites store the category as a plain string, so renames are NOT
 * a database schema change. Old (round-5) values are parsed by
 * [safeValueOf] and mapped to the new categories for display; the
 * stored string is never rewritten.
 */
enum class InspirationCategory(val label: String) {
    ALL("全部"),
    ANIME("二次元插画"),
    REALISTIC("写实插画"),
    PHOTOGRAPHY("情绪摄影"),
    ARCHITECTURE("建筑美学"),
    LANDSCAPE("风景与氛围"),
    /** Fallback bucket for items that cannot be classified confidently. */
    OTHER("其他");

    companion object {
        /** Chips shown in the directory: ALL + the five categories.
         * OTHER is a fallback bucket only and never appears as a chip. */
        val visibleCategories: List<InspirationCategory> =
            listOf(ALL, ANIME, REALISTIC, PHOTOGRAPHY, ARCHITECTURE, LANDSCAPE)

        /**
         * Parse a stored category string without crashing. Round-5
         * values are mapped to the new five categories for display;
         * anything unknown lands in OTHER instead of throwing.
         * The stored string is never rewritten — favorites keep their
         * original category.
         */
        fun safeValueOf(name: String?): InspirationCategory = when (name) {
            null -> OTHER
            "ALL" -> ALL
            "ANIME" -> ANIME
            "REALISTIC" -> REALISTIC
            "PHOTOGRAPHY" -> PHOTOGRAPHY
            "ARCHITECTURE" -> ARCHITECTURE
            "LANDSCAPE" -> LANDSCAPE
            "OTHER" -> OTHER
            // Round-5 legacy values.
            "ILLUSTRATION" -> ANIME
            "CHARACTER_DESIGN" -> ANIME
            "CONCEPT_ART" -> REALISTIC
            "INTERIOR" -> ARCHITECTURE
            "ABSTRACT" -> OTHER
            else -> OTHER
        }
    }
}

/**
 * A favorited recommendation. Stores the work's metadata + links only —
 * no local copy of the image. Favorites survive feed refreshes: they
 * live in their own table and were never touched by cache replacement.
 * [note] is written by the user only — never generated.
 *
 * Read-only since Artlog 2.0 stage 3: the UI only reads these rows
 * (via `InspirationRepository.favorites`, already gate-downgraded).
 * The Repository exposes no write methods any more; the only writes
 * are the backup/restore row swap in `BackupManager`, which goes
 * through `InspirationDao` directly and preserves rows exactly.
 */
@Entity(
    tableName = "inspiration_favorites",
    indices = [Index("savedAt")]
)
data class InspirationFavorite(
    @PrimaryKey val id: String,
    val source: String,
    val sourceId: String,
    val title: String,
    val author: String?,
    val sourceUrl: String,
    /** Nullable — LINK rows store null here. */
    val imageUrl: String?,
    val thumbnailUrl: String?,
    val width: Int?,
    val height: Int?,
    val category: String,
    val description: String?,
    val license: String?,
    val licenseUrl: String?,
    val dateText: String?,
    val place: String?,
    /** InspirationContentType.name. */
    val contentType: String,
    /** InspirationLicenseStatus.name. */
    val licenseStatus: String,
    val savedAt: Long,
    /** Personal note, user-written only. */
    val note: String = "",
) {
    fun toWork(): InspirationWork = InspirationWork(
        id = id,
        source = InspirationSource.safeValueOf(source),
        sourceId = sourceId,
        title = title,
        author = author,
        sourceUrl = sourceUrl,
        imageUrl = imageUrl,
        thumbnailUrl = thumbnailUrl,
        width = width,
        height = height,
        category = InspirationCategory.safeValueOf(category),
        description = description,
        license = license,
        licenseUrl = licenseUrl,
        dateText = dateText,
        place = place,
        contentType = InspirationContentType.safeValueOf(contentType),
        licenseStatus = InspirationLicenseStatus.safeValueOf(licenseStatus),
        fetchedAt = null,
    )

    companion object {
        fun fromWork(work: InspirationWork, savedAt: Long): InspirationFavorite =
            InspirationFavorite(
                id = work.id,
                source = work.source.name,
                sourceId = work.sourceId,
                title = work.title,
                author = work.author,
                sourceUrl = work.sourceUrl,
                imageUrl = work.imageUrl,
                thumbnailUrl = work.thumbnailUrl,
                width = work.width,
                height = work.height,
                category = work.category.name,
                description = work.description,
                license = work.license,
                licenseUrl = work.licenseUrl,
                dateText = work.dateText,
                place = work.place,
                contentType = work.contentType.name,
                licenseStatus = work.licenseStatus.name,
                savedAt = savedAt,
            )
    }
}
