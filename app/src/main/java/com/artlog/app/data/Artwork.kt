package com.artlog.app.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * Artwork status (spec V1.0, section 3.2). Stored as the enum name;
 * [ArtworkStatus.label] is the Chinese label shown in the UI.
 */
enum class ArtworkStatus(val label: String) {
    DRAFT("草稿"),
    IN_PROGRESS("进行中"),
    COMPLETED("已完成"),
    ARCHIVED("已归档");

    companion object {
        fun fromName(name: String): ArtworkStatus =
            entries.firstOrNull { it.name == name } ?: DRAFT
    }
}

/**
 * Artwork (spec V1.0, section 4.1).
 *
 * - [createdAt] is the user-chosen creation date (epoch millis, start of day).
 * - [addedAt] / [updatedAt] are bookkeeping timestamps set by the app.
 * - [durationMinutes] is nullable: an artwork without a filled-in duration
 *   is excluded from total-duration statistics (spec section 3.6).
 * - [description] is the creation note; [intention] / [reflection] /
 *   [improvement] are the creation-summary fields (spec section 3.4):
 *   创作初衷 / 自己满意的地方 / 下次改进的方向. "遇到的问题" is recorded
 *   inside [description] (the creation note) in round 2.
 * - [coverPath] is the absolute path of the cover image file, or null when
 *   the artwork has no images (not expected in normal use).
 */
@Entity(tableName = "artworks")
data class Artwork(
    @PrimaryKey val id: String,
    val title: String,
    val coverPath: String?,
    val createdAt: Long,
    val addedAt: Long,
    val updatedAt: Long,
    val durationMinutes: Int?,
    val status: String,
    val description: String,
    val intention: String,
    val reflection: String,
    val improvement: String
)

/**
 * One image belonging to an artwork (spec V1.0, section 4.2).
 *
 * - [imagePath] / [thumbnailPath] are absolute paths under the app-private
 *   storage directory; originals are never compressed or overwritten.
 * - [stage] records the creation stage of this image. The stage system
 *   (Sketch / Lineart / Base Color / Rendering / Final) is NOT built in
 *   round 2; imported images are stored with stage "ORIGINAL" until the
 *   stage feature lands in a later round.
 * - Rows are deleted automatically with their artwork (CASCADE).
 */
@Entity(
    tableName = "artwork_images",
    foreignKeys = [
        ForeignKey(
            entity = Artwork::class,
            parentColumns = ["id"],
            childColumns = ["artworkId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("artworkId")]
)
data class ArtworkImage(
    @PrimaryKey val id: String,
    val artworkId: String,
    val imagePath: String,
    val thumbnailPath: String,
    val stage: String,
    val sortOrder: Int,
    val createdAt: Long,
    val note: String
)

/** An artwork together with its images, in display order. */
data class ArtworkWithImages(
    @Embedded val artwork: Artwork,
    @Relation(parentColumn = "id", entityColumn = "artworkId")
    val images: List<ArtworkImage>
)
