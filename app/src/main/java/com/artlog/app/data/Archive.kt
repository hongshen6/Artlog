package com.artlog.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * User-defined tag (round 3). Tag names are unique: creating a tag with
 * an existing name reuses the existing tag instead of duplicating it.
 * Suggested uses: 角色 / 场景 / 练习 / 原创 / 临摹 — but any name works.
 */
@Entity(
    tableName = "tags",
    indices = [Index(value = ["name"], unique = true)]
)
data class Tag(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long
)

/**
 * Many-to-many link between artworks and tags. Both foreign keys
 * cascade: deleting an artwork removes its links, deleting a tag
 * removes its links — but neither deletion removes the other side's
 * rows (deleting a tag never deletes an artwork).
 */
@Entity(
    tableName = "artwork_tag_cross_ref",
    primaryKeys = ["artworkId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = Artwork::class,
            parentColumns = ["id"],
            childColumns = ["artworkId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Tag::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("artworkId"), Index("tagId")]
)
data class ArtworkTagCrossRef(
    val artworkId: String,
    val tagId: String
)

/**
 * Journal entry (round 3): a dated note about one drawing session —
 * what was thought through, what went wrong, what got done.
 * [artworkId] is optional: an entry may stand alone, or link to one
 * artwork. Deleting the artwork unlinks the entry (SET_NULL) instead
 * of deleting the note.
 */
@Entity(
    tableName = "journal_entries",
    foreignKeys = [
        ForeignKey(
            entity = Artwork::class,
            parentColumns = ["id"],
            childColumns = ["artworkId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("artworkId")]
)
data class JournalEntry(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val artworkId: String?
)

/** Monthly new-artwork counts, from a GROUP BY over createdAt. */
data class MonthCount(
    val month: String,
    val count: Int
)

/** Artwork counts per status. */
data class StatusCount(
    val status: String,
    val count: Int
)

/** Completed-artwork counts per year. */
data class YearCount(
    val year: String,
    val count: Int
)

/** Artwork counts per day, for the creation calendar heatmap. */
data class DayCount(
    val day: String,
    val count: Int
)
