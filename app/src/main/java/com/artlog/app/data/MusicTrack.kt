package com.artlog.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 音乐曲目（下一阶段迭代，数据库 v6 新表 `music_tracks`）。
 *
 * 只存元数据与路径，音频文件本身放私有目录
 * `filesDir/Artlog/music/<id>.<ext>`（导入时按字节复制，不转码、
 * 不改名、不压缩；LRC 另存 `filesDir/Artlog/music/lyrics/<id>.lrc`）。
 * 导入流程（SAF 选取 → 复制 → 读元数据 → 写行）由后续阶段做，
 * 本阶段只做数据层。
 *
 * - [lyricsSource] 为枚举名存 String（NONE / EMBEDDED / LRC），沿用
 *   `inspiration_favorites` 的存法；[lyricsText] 存歌词原文（含时
 *   间标签），无则 null；内嵌歌词时 [lyricsFilePath] 为 null。
 * - 删曲目 = 删行 + 删音频文件 + 删 LRC 文件（后续阶段的删除流程
 *   负责文件部分；本 Repository 的 [MusicTrackRepository.deleteTrack]
 *   只删行，见该方法注释）。
 * - [coverPath]（数据库 v7）：音频内嵌封面的缓存文件绝对路径
 *   （`filesDir/Artlog/music/covers/<trackId>.jpg|.png`），无内嵌
 *   封面或提取失败时为 null；与作品 [Artwork.coverPath] 同模式
 *   （可空绝对路径，不存字节进库）。展示面按
 *   `coverPath != null → 加载真实图 / 否则占位图` 回落。
 *
 * 最小 v6 起注册进 [ArtworkDatabase]（entities 清单 +
 * MIGRATION_5_6 建表），经 [MusicTrackDao] 与
 * [MusicTrackRepository] 接线；音频与 LRC 文件随 ZIP 备份（manifest
 * 计数字段 `musicTrackCount`，逐项校验存在性）。
 */
@Entity(
    tableName = "music_tracks",
    indices = [Index("importedAt"), Index("title")]
)
data class MusicTrack(
    /** `track:<uuid>`。 */
    @PrimaryKey val id: String,
    /** 优先取内嵌元数据，否则取文件名（去扩展名）。 */
    val title: String,
    /** 内嵌元数据，无则 null。 */
    val artist: String?,
    val album: String?,
    /** MediaMetadataRetriever 读取；读不到为 null，不阻塞导入。 */
    val durationMs: Long?,
    /** 私有目录内的文件名。 */
    val fileName: String,
    /** 绝对路径 filesDir/Artlog/music/<id>.<ext>。 */
    val filePath: String,
    val fileSizeBytes: Long,
    /** audio/mpeg、audio/mp4 等。 */
    val mimeType: String?,
    /** 导入时提取/复制的歌词原文（含时间标签），无则 null。 */
    val lyricsText: String?,
    /** NONE / EMBEDDED / LRC（枚举名存 String）。 */
    val lyricsSource: String,
    /** LRC 文件复制后的路径（filesDir/Artlog/music/lyrics/<id>.lrc），内嵌歌词为 null。 */
    val lyricsFilePath: String?,
    /** 导入时间（毫秒时间戳）。 */
    val importedAt: Long,
    /**
     * 内嵌封面缓存文件绝对路径（数据库 v7 新增，可空）。
     * 放在最后一位：Room 生成的 v7 建表 SQL 把它排在末列，与
     * MIGRATION_6_7 的 ALTER TABLE ADD COLUMN 追加位置一致，
     * 迁移后表结构与 Room 期望完全相同。默认 null 兼容旧构造
     * 调用（旧行迁移后亦为 null）。
     */
    val coverPath: String? = null,
)
