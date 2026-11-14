package com.artlog.app.data

import java.io.File

/**
 * 专辑封面纯逻辑（最终完善轮，数据库 v7）。
 *
 * 封面只取自用户主动导入的本地音频文件的内嵌图：导入时
 * `MediaMetadataRetriever.getEmbeddedPicture()` 在同一会话内多取
 * 一项，成功则以原字节写盘到 `filesDir/Artlog/music/covers/`，
 * 每首歌一份独立文件（不做哈希去重、不引入引用计数，删除时只删
 * 本首歌自己的文件，杜绝误删他人封面）。不联网、不调第三方
 * API、不按歌名下载图片、不扫描媒体库、不改原始音频文件。
 *
 * 本对象只含纯函数（无 Android 框架调用），由 JVM 测试
 * TestAlbumArtwork 覆盖；真正碰 Retriever / 文件 IO 的部分在
 * [MusicImport] / [MusicStorage] / [CoverBackfill]，JVM 测不了
 * Retriever 的逐格式行为（见实施报告）。
 */
object AlbumCoverLogic {

    /** JPEG 文件头：FF D8 FF（只看前 3 字节，足够与 PNG 区分）。 */
    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

    /** PNG 文件头：89 50 4E 47 0D 0A 1A 0A（8 字节签名）。 */
    private val PNG_MAGIC = byteArrayOf(
        0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
        0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()
    )

    /**
     * 按图片字节头判断扩展名（不信任内嵌声明的 MIME / 类型）：
     * JPEG → "jpg"，PNG → "png"，其他（空、过短、GIF/WebP/BMP、
     * 损坏数据）→ null（调用方按“无封面”处理，不阻塞导入）。
     * 只认 JPEG 与 PNG：内嵌封面绝大多数是这两者；GIF 动图与
     * WebP 在 MediaMetadataRetriever 返回的内嵌图中罕见，暂不
     * 支持——误判为无封面比写错扩展名后加载失败更安全。
     */
    fun extensionForImageBytes(bytes: ByteArray?): String? {
        if (bytes == null) return null
        if (bytes.size >= 3 && bytes.startsWith(JPEG_MAGIC)) return "jpg"
        if (bytes.size >= PNG_MAGIC.size && bytes.startsWith(PNG_MAGIC)) return "png"
        return null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }

    /**
     * 封面文件名：`<trackId 去冒号>.jpg|.png`，与音频 / LRC 的
     * 命名规则一致（见 [MusicStorage.fileNameFor]）。返回 null
     * 表示字节头无法识别，调用方不写文件。
     */
    fun coverFileName(trackId: String, imageBytes: ByteArray?): String? {
        val extension = extensionForImageBytes(imageBytes) ?: return null
        return "${MusicStorage.fileNameFor(trackId)}.$extension"
    }

    /** 该曲目是否需要回填（只有 coverPath 为空的旧曲目才处理）。 */
    fun needsBackfill(track: MusicTrack): Boolean = track.coverPath.isNullOrBlank()

    /** 从曲库中挑出需要回填的曲目（coverPath 为空的才处理）。 */
    fun selectForBackfill(tracks: List<MusicTrack>): List<MusicTrack> =
        tracks.filter { needsBackfill(it) }

    /**
     * mediaItemFor 的 artwork 决策（纯函数）：有封面路径返回该
     * 路径（调用方再包成 `Uri.fromFile`），无封面返回 null（调用
     * 方不写 artwork，不伪造）。空白字符串按无封面处理。
     */
    fun artworkPathFor(track: MusicTrack): String? =
        track.coverPath?.takeIf { it.isNotBlank() }

    /**
     * 备份时封面缺失的决策（纯函数，最终完善轮交付复核后改为降级）：
     * 封面是可从音频文件重新提取的派生数据，不是用户原始数据；
     * 备份时因一张可再生的封面缺失而让整份备份失败，会连累用户
     * 无法备份原始数据。规则：有 coverPath 但文件不存在 → 跳过
     * 该封面并计入降级（备份继续）；无 coverPath → 无封面可处理，
     * 不计降级；文件存在 → 正常复制。音频 / LRC / 作品图片缺失
     * 仍按原有 fail-fast 规则失败——那些是用户原始数据。
     */
    fun shouldDegradeMissingCover(coverPath: String?, coverFileExists: Boolean): Boolean =
        !coverPath.isNullOrBlank() && !coverFileExists

    /** 对一曲库计数备份时应降级的封面（有 coverPath 但文件缺失）。 */
    fun countDegradedCovers(
        tracks: List<MusicTrack>,
        coverExists: (String) -> Boolean
    ): Int = tracks.count { track ->
        val path = track.coverPath?.takeIf { it.isNotBlank() } ?: return@count false
        !coverExists(path)
    }

    /**
     * 删除时要删的封面文件：只有 coverPath 同时满足两点才返回该
     * 文件——(1) 位于本 App 封面目录（`coversDir`）内；(2) 文件名
     * 就是本曲目的封面文件名（`<trackId 去冒号>.jpg|.png`）。任
     * 一不满足返回 null（不删）。这是“不删他人封面”的第二道防线：
     * 即便某行的 coverPath 被写坏（指向其他目录、指向另一首歌的
     * 封面），删除流程也不删任何文件；文件名本身由 trackId 派生，
     * 每首歌一份独立文件是第一道防线。
     */
    fun coverFileToDelete(track: MusicTrack, coversDir: File): File? {
        val path = track.coverPath?.takeIf { it.isNotBlank() } ?: return null
        val file = File(path)
        if (file.name != "${MusicStorage.fileNameFor(track.id)}.jpg" &&
            file.name != "${MusicStorage.fileNameFor(track.id)}.png"
        ) {
            return null
        }
        val parent = try {
            file.parentFile?.canonicalPath
        } catch (_: Exception) {
            return null
        }
        val dir = try {
            coversDir.canonicalPath
        } catch (_: Exception) {
            return null
        }
        return if (parent == dir) file else null
    }
}
