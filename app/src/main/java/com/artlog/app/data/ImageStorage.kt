package com.artlog.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Local file storage (spec V1.0, section 5).
 *
 * Layout under the app-private files directory (no other app or the user
 * can accidentally modify it, and it is removed with the app):
 *
 *     filesDir/Artlog/artworks/<artwork-uuid>/original/<image-uuid>.<ext>
 *     filesDir/Artlog/artworks/<artwork-uuid>/thumbnails/<image-uuid>.jpg
 *
 * Rules enforced here:
 * - Imported images are COPIED from the picker URI; the original gallery
 *   URI is never stored or relied on long-term.
 * - Originals are copied byte-for-byte: never compressed, never overwritten.
 * - Thumbnails are generated separately (max 512px, JPEG quality 85) and
 *   never replace the original.
 * - Deleting an artwork removes its whole directory (originals +
 *   thumbnails) together with the database rows.
 */
object ImageStorage {

    private const val ROOT_DIR = "Artlog"
    private const val THUMBNAIL_MAX_PX = 512
    private const val THUMBNAIL_QUALITY = 85

    fun artworkDir(context: Context, artworkId: String): File =
        File(context.filesDir, "$ROOT_DIR/artworks/$artworkId")

    fun originalDir(context: Context, artworkId: String): File =
        File(artworkDir(context, artworkId), "original")

    fun thumbnailDir(context: Context, artworkId: String): File =
        File(artworkDir(context, artworkId), "thumbnails")

    /** Result of copying one picked image into app storage. */
    data class ImportedImage(
        val imagePath: String,
        val thumbnailPath: String
    )

    /**
     * Copy picked images into app storage and generate thumbnails.
     * Runs on the caller's (IO) dispatcher. Throws on failure; callers
     * must treat a failed import as "nothing saved" and keep the form.
     */
    fun copyImages(
        context: Context,
        artworkId: String,
        uris: List<Uri>
    ): List<ImportedImage> {
        val originals = originalDir(context, artworkId).apply { mkdirs() }
        val thumbnails = thumbnailDir(context, artworkId).apply { mkdirs() }
        val resolver = context.contentResolver
        return uris.map { uri ->
            val imageId = UUID.randomUUID().toString()
            val extension = extensionFor(resolver.getType(uri))
            val originalFile = File(originals, "$imageId.$extension")
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(originalFile).use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("无法读取图片: $uri")
            val thumbnailFile = File(thumbnails, "$imageId.jpg")
            // If thumbnail generation fails, fall back to the original as
            // the thumbnail path so the image still displays; the original
            // itself is untouched either way.
            val thumbnailPath = try {
                generateThumbnail(originalFile, thumbnailFile)
                thumbnailFile.absolutePath
            } catch (_: Exception) {
                originalFile.absolutePath
            }
            ImportedImage(
                imagePath = originalFile.absolutePath,
                thumbnailPath = thumbnailPath
            )
        }
    }

    /** Delete an artwork's whole storage directory. Returns false if the
     * directory is already gone; failures are reported, not hidden. */
    fun deleteArtworkFiles(context: Context, artworkId: String): Boolean {
        val dir = artworkDir(context, artworkId)
        return if (dir.exists()) dir.deleteRecursively() else true
    }

    private fun extensionFor(mimeType: String?): String = when (mimeType) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/jpeg", "image/jpg" -> "jpg"
        else -> "jpg"
    }

    /**
     * Downscale [source] into a JPEG thumbnail at most [THUMBNAIL_MAX_PX]
     * on its longest side, preserving aspect ratio.
     */
    private fun generateThumbnail(source: File, dest: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sampleSize = 1
        while (maxSide / sampleSize > 1024) sampleSize *= 2
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeFile(source.absolutePath, decodeOptions)
            ?: throw IllegalStateException("无法解码图片: ${source.absolutePath}")
        try {
            val scale = THUMBNAIL_MAX_PX.toFloat() / maxOf(bitmap.width, bitmap.height)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bitmap
            }
            try {
                FileOutputStream(dest).use { out ->
                    if (!scaled.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, out)) {
                        throw IllegalStateException("缩略图压缩失败: ${dest.absolutePath}")
                    }
                }
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
