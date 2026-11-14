package com.artlog.app.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * 音乐文件存储（下一阶段迭代，阶段 5）。
 *
 * 复用 [ImageStorage] 的模式：导入的音频与 LRC 一律按字节复制到
 * App 私有目录，不转码、不改名（扩展名保留）、不压缩；原始文件
 * 永不移动、不删除。布局：
 *
 *     filesDir/Artlog/music/<trackId>.<ext>
 *     filesDir/Artlog/music/lyrics/<trackId>.lrc
 *     filesDir/Artlog/music/covers/<trackId>.jpg|.png
 *       （数据库 v7：音频内嵌封面的缓存文件，每首歌一份独立
 *       文件，扩展名按图片字节头判断，见 AlbumCoverLogic；
 *       不做哈希去重、不引入引用计数）
 *
 * 其中 `<trackId>` 是 `track:<uuid>` 去掉冒号后的文件名形式
 * （见 [fileNameFor]；冒号不能直接做文件名）。
 *
 * 复制失败抛异常，调用方须按“导入失败、不写脏行”处理（先删已
 * 复制的部分文件，再报错）。删除失败同样抛异常、不静默留孤儿
 * 文件（见 [MusicImport]）。
 */
object MusicStorage {

    const val MUSIC_DIR = "Artlog/music"
    const val LYRICS_DIR = "Artlog/music/lyrics"
    /** 数据库 v7：内嵌封面缓存目录（每首歌一份独立文件）。 */
    const val COVERS_DIR = "Artlog/music/covers"

    /** `track:<uuid>` → 可做文件名的 `track-<uuid>`。 */
    fun fileNameFor(trackId: String): String = trackId.replace(":", "-")

    fun musicDir(context: Context): File =
        File(context.filesDir, MUSIC_DIR).apply { mkdirs() }

    fun lyricsDir(context: Context): File =
        File(context.filesDir, LYRICS_DIR).apply { mkdirs() }

    /** 封面缓存目录（数据库 v7）。 */
    fun coversDir(context: Context): File =
        File(context.filesDir, COVERS_DIR).apply { mkdirs() }

    /** 曲目音频文件的目标位置（不创建文件，只算路径）。 */
    fun audioFile(context: Context, trackId: String, extension: String): File =
        File(musicDir(context), "${fileNameFor(trackId)}.$extension")

    /** 曲目 LRC 文件的目标位置（不创建文件，只算路径）。 */
    fun lyricsFile(context: Context, trackId: String): File =
        File(lyricsDir(context), "${fileNameFor(trackId)}.lrc")

    /**
     * 曲目封面缓存文件的目标位置（不创建文件，只算路径）。
     * 文件名由 [AlbumCoverLogic.coverFileName] 算出（扩展名按字节
     * 头判断）；这里只做路径拼接。
     */
    fun coverFile(context: Context, trackId: String, imageBytes: ByteArray?): File? {
        val name = AlbumCoverLogic.coverFileName(trackId, imageBytes) ?: return null
        return File(coversDir(context), name)
    }

    /**
     * 把内嵌封面字节写盘到封面缓存目录。字节头无法识别（非
     * JPEG/PNG）或写盘失败时返回 null（调用方按“无封面”处理，
     * 不阻塞导入）；失败时已写的部分文件删干净。
     */
    fun saveCover(context: Context, trackId: String, imageBytes: ByteArray?): File? {
        val dest = coverFile(context, trackId, imageBytes) ?: return null
        return try {
            dest.parentFile?.mkdirs()
            dest.writeBytes(imageBytes!!)
            if (dest.length() == 0L) {
                dest.delete()
                null
            } else {
                dest
            }
        } catch (_: Exception) {
            dest.delete()
            null
        }
    }

    /**
     * 把音频 URI 按字节复制到私有目录。返回目标文件。
     * 失败时抛异常，并尽力删掉已复制的部分文件。
     */
    fun copyAudio(
        context: Context,
        trackId: String,
        uri: Uri,
        extension: String
    ): File {
        val dest = audioFile(context, trackId, extension)
        copyBytes(context, uri, dest)
        return dest
    }

    /**
     * 把 LRC URI 按字节复制到 `lyrics/` 子目录。返回目标文件。
     * 失败时抛异常，并尽力删掉已复制的部分文件。
     */
    fun copyLyrics(context: Context, trackId: String, uri: Uri): File {
        val dest = lyricsFile(context, trackId)
        copyBytes(context, uri, dest)
        return dest
    }

    private fun copyBytes(context: Context, uri: Uri, dest: File) {
        dest.parentFile?.mkdirs()
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("无法读取文件: $uri")
            if (dest.length() == 0L) throw IllegalStateException("文件为空: $uri")
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
    }

    /**
     * 删除一首曲目的音频文件、LRC 文件与封面缓存文件（只删 App
     * 私有目录中的副本，不删原始文件）。返回删不掉的文件路径清
     * 单；空清单表示全部删干净（文件本就不存在也算删干净）。
     * 封面只删本首歌自己的文件：[AlbumCoverLogic.coverFileToDelete]
     * 限定只删封面目录内的文件，coverPath 为空、指向其他目录或
     * 写坏时不删任何文件（不删他人封面、不删音频与 LRC）。
     */
    fun deleteTrackFiles(context: Context, track: MusicTrack): List<String> {
        val failed = mutableListOf<String>()
        val audio = File(track.filePath)
        if (audio.exists() && !audio.delete()) failed.add(track.filePath)
        val lyricsPath = track.lyricsFilePath
        if (lyricsPath != null) {
            val lyrics = File(lyricsPath)
            if (lyrics.exists() && !lyrics.delete()) failed.add(lyricsPath)
        }
        // 只算路径，不建目录：删除流程不应因删文件而新建空目录。
        val cover = AlbumCoverLogic.coverFileToDelete(
            track, File(context.filesDir, COVERS_DIR)
        )
        if (cover != null && cover.exists() && !cover.delete()) {
            failed.add(cover.absolutePath)
        }
        return failed
    }

    /**
     * 按 MIME 类型与显示文件名推断音频扩展名（不含点）。
     * 支持的格式以实际解码器能力为准：MP3、M4A、AAC、FLAC、WAV、
     * OGG。未知类型时用文件名的扩展名兜底；两者都没有则返回 null，
     * 调用方须按导入失败处理（不猜扩展名、不写脏行）。
     */
    fun extensionFor(mimeType: String?, displayName: String?): String? {
        when (mimeType?.lowercase()) {
            "audio/mpeg", "audio/mp3" -> return "mp3"
            "audio/mp4", "audio/x-m4a" -> return "m4a"
            "audio/aac" -> return "aac"
            "audio/flac", "audio/x-flac" -> return "flac"
            "audio/wav", "audio/x-wav", "audio/wave" -> return "wav"
            "audio/ogg", "audio/vorbis" -> return "ogg"
        }
        val fromName = displayName
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it in SUPPORTED_EXTENSIONS }
        if (fromName != null) return fromName
        return null
    }

    val SUPPORTED_EXTENSIONS: Set<String> = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg")

    /** 文件名是否为支持的音频格式（按扩展名判断）。 */
    fun isSupportedAudioFile(displayName: String?): Boolean {
        val ext = displayName?.substringAfterLast('.', "")?.lowercase()
        return ext in SUPPORTED_EXTENSIONS
    }

    /** 文件名是否为 LRC 文件（按扩展名判断）。 */
    fun isLyricsFile(displayName: String?): Boolean =
        displayName?.lowercase()?.endsWith(".lrc") == true
}
