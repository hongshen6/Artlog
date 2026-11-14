package com.artlog.app.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 音乐导入流程（下一阶段迭代，阶段 5）。
 *
 * SAF 选取 → 复制到私有目录 → `MediaMetadataRetriever` 读标题/
 * 艺术家/专辑/时长/内嵌封面（仅元数据；歌词不在此列）→ 读内嵌/LRC 歌词
 * → 写 `music_tracks` 行。
 *
 * 封面（数据库 v7）：同一 Retriever 会话内多取一项
 * `getEmbeddedPicture()`，成功则以原字节写盘到
 * `filesDir/Artlog/music/covers/<trackId>.jpg|.png`（扩展名按图片
 * 字节头判断，不信任内嵌声明），失败 / 无图 / 字节头无法识别时
 * `coverPath = null`，不阻塞导入（与“读不到为 null”语义一致）。
 * 原始音乐文件保持不变；不联网、不调第三方 API、不按歌名下载。
 *
 * 失败语义（硬性）：格式不支持、复制失败、元数据读取失败等一律
 * 抛异常，**不写脏行**；已复制的部分文件在抛错前删干净。
 *
 * 不做的事：不自动扫描媒体库/存储，不在线搜索音乐，不导入音乐
 * URL，不自动下载，不经用户操作读取其他音乐文件；不申请
 * `READ_MEDIA_AUDIO`（SAF 复制路线，URI 临时授权即可）。
 * 不设人为数量上限。
 *
 * 歌词优先级：用户一起选的同名 LRC > 内嵌歌词 > 无歌词。
 * LRC 追加入口（曲目详情内单独选 LRC）见 [attachLyrics]。
 */
class MusicImport(
    context: Context,
    private val repository: MusicTrackRepository
) {
    private val appContext = context.applicationContext

    /** 一次批量导入的结果：成功与失败分开报告，不互相掩盖。 */
    data class ImportResult(
        val imported: List<MusicTrack>,
        /** 失败项：(显示文件名, 原因)。 */
        val failed: List<Pair<String, String>>
    )

    /**
     * 导入单首音频。lrcUri 为用户一起选的同名 LRC（可空）。
     * 成功返回写库后的 [MusicTrack]；失败抛异常且不留脏行与残文件。
     * 必须在协程中调用；IO 操作在 Dispatchers.IO 上执行。
     */
    suspend fun importTrack(audioUri: Uri, lrcUri: Uri? = null): MusicTrack =
        withContext(Dispatchers.IO) {
            val displayName = displayNameOf(audioUri) ?: "unknown"
            val mimeType = appContext.contentResolver.getType(audioUri)
            val extension = MusicStorage.extensionFor(mimeType, displayName)
                ?: throw IllegalArgumentException("不支持的音频格式: $displayName")
            val trackId = "track:${UUID.randomUUID()}"
            var audioFile: File? = null
            var lyricsFile: File? = null
            var coverFile: File? = null
            try {
                audioFile = MusicStorage.copyAudio(appContext, trackId, audioUri, extension)
                val metadata = readMetadata(audioFile)
                // 封面：内嵌图成功才写盘；无图 / 写盘失败按无封面处理，
                // 不阻塞导入。
                val coverPath = MusicStorage.saveCover(
                    appContext, trackId, metadata.coverBytes
                )?.also { coverFile = it }?.absolutePath
                val title = titleFromMetadata(metadata.title, displayName)
                // 歌词：LRC 优先于内嵌歌词。
                var lyricsText: String? = null
                var lyricsSource = "NONE"
                var lyricsFilePath: String? = null
                if (lrcUri != null) {
                    val lrcText = readLrcText(lrcUri)
                    if (!lrcText.isNullOrBlank()) {
                        lyricsFile = MusicStorage.copyLyrics(appContext, trackId, lrcUri)
                        lyricsText = lrcText
                        lyricsSource = "LRC"
                        lyricsFilePath = lyricsFile.absolutePath
                    }
                }
                if (lyricsText == null) {
                    val embedded = LyricsParser.readEmbeddedLyrics(audioFile)
                    if (!embedded.isNullOrBlank()) {
                        lyricsText = embedded
                        lyricsSource = "EMBEDDED"
                    }
                }
                val track = MusicTrack(
                    id = trackId,
                    title = title,
                    artist = metadata.artist,
                    album = metadata.album,
                    durationMs = metadata.durationMs,
                    fileName = audioFile.name,
                    filePath = audioFile.absolutePath,
                    fileSizeBytes = audioFile.length(),
                    mimeType = mimeType,
                    lyricsText = lyricsText,
                    lyricsSource = lyricsSource,
                    lyricsFilePath = lyricsFilePath,
                    importedAt = System.currentTimeMillis(),
                    coverPath = coverPath
                )
                repository.insertTrack(track)
                track
            } catch (e: Exception) {
                // 不写脏行：行还没写（insert 在最后），只清已复制的文件。
                audioFile?.delete()
                lyricsFile?.delete()
                coverFile?.delete()
                throw e
            }
        }

    /**
     * 批量导入（SAF 多选的结果）。音频与 LRC 按“去掉扩展名的同名”
     * 配对：与某音频同名的 LRC 一并复制；无同名 LRC 的音频正常导
     * 入（无歌词）；落单的 LRC（无对应音频）计入失败，不单独入库。
     * 单首失败不影响其他首；全部结果汇总在 [ImportResult] 中。
     */
    suspend fun importTracks(uris: List<Uri>): ImportResult {
        val imported = mutableListOf<MusicTrack>()
        val failed = mutableListOf<Pair<String, String>>()
        val audioUris = mutableListOf<Uri>()
        val lrcByBaseName = mutableMapOf<String, Uri>()
        for (uri in uris) {
            val name = displayNameOf(uri) ?: continue
            when {
                MusicStorage.isLyricsFile(name) ->
                    lrcByBaseName[baseNameOf(name)] = uri
                MusicStorage.isSupportedAudioFile(name) ->
                    audioUris.add(uri)
                else -> failed.add(name to "不支持的格式")
            }
        }
        for (audioUri in audioUris) {
            val name = displayNameOf(audioUri) ?: "unknown"
            val lrcUri = lrcByBaseName[baseNameOf(name)]
            try {
                imported.add(importTrack(audioUri, lrcUri))
            } catch (e: Exception) {
                failed.add(name to (e.message ?: "导入失败"))
            }
        }
        for ((baseName, _) in lrcByBaseName) {
            if (audioUris.none { baseNameOf(displayNameOf(it) ?: "") == baseName }) {
                failed.add("$baseName.lrc" to "没有对应的音频文件")
            }
        }
        return ImportResult(imported, failed)
    }

    /**
     * 曲目详情内的 LRC 追加入口：用户确认关联后，把选中的 LRC
     * 复制到私有目录（不移动、不删除原始歌词文件），并更新该曲目
     * 的歌词字段。用户明确追加的 LRC 优先于内嵌歌词。
     * 失败抛异常，旧歌词与旧文件保持不变。
     */
    suspend fun attachLyrics(track: MusicTrack, lrcUri: Uri): MusicTrack =
        withContext(Dispatchers.IO) {
            val lrcText = readLrcText(lrcUri)
                ?: throw IllegalArgumentException("无法读取歌词文件")
            if (lrcText.isBlank()) throw IllegalArgumentException("歌词文件为空")
            val newFile = MusicStorage.copyLyrics(appContext, track.id, lrcUri)
            val oldPath = track.lyricsFilePath
            try {
                val updated = track.copy(
                    lyricsText = lrcText,
                    lyricsSource = "LRC",
                    lyricsFilePath = newFile.absolutePath
                )
                repository.updateTrack(updated)
                if (oldPath != null && oldPath != newFile.absolutePath) {
                    File(oldPath).delete()
                }
                updated
            } catch (e: Exception) {
                newFile.delete()
                throw e
            }
        }

    /**
     * 删除曲目：先删私有目录中的音频与 LRC 副本，删干净后再删数据
     * 库行。删文件失败抛异常、**不删行**，不静默留孤儿文件；只删
     * Artlog 私有目录中的副本，不删原始文件。调用方（播放器）在删
     * 当前播放歌曲前须先安全停止或切换（见 MusicViewModel）。
     */
    suspend fun deleteTrack(track: MusicTrack) {
        withContext(Dispatchers.IO) {
            val failed = MusicStorage.deleteTrackFiles(appContext, track)
            if (failed.isNotEmpty()) {
                throw IllegalStateException("无法删除文件: ${failed.joinToString()}")
            }
            repository.deleteTrack(track.id)
        }
    }

    /** 读 SAF URI 的显示文件名（OpenableColumns.DISPLAY_NAME）。 */
    fun displayNameOf(uri: Uri): String? {
        return try {
            appContext.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else null
            } ?: uri.lastPathSegment?.substringAfterLast('/')
        } catch (_: Exception) {
            uri.lastPathSegment?.substringAfterLast('/')
        }
    }

    private fun baseNameOf(displayName: String): String =
        displayName.substringBeforeLast('.', displayName)

    private data class AudioMetadata(
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long?,
        /** 内嵌封面原字节；无图或读取失败为 null，不阻塞导入。 */
        val coverBytes: ByteArray?
    )

    /**
     * 只读元数据（标题/艺术家/专辑/时长/内嵌封面）。
     * `MediaMetadataRetriever` 无歌词项，歌词不在此列。读不到的字段
     * 为 null，不阻塞导入。封面与元数据共用同一个 Retriever 会话
     * （同一 `setDataSource`），不重复开文件；`getEmbeddedPicture()`
     * 抛错时按无封面处理，不让封面读取失败拖垮整首导入。
     */
    private fun readMetadata(audioFile: File): AudioMetadata {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(audioFile.absolutePath)
            val duration = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull()
            val coverBytes = try {
                retriever.embeddedPicture
            } catch (_: Exception) {
                null
            }
            return AudioMetadata(
                title = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_TITLE
                )?.trim()?.takeIf { it.isNotEmpty() },
                artist = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_ARTIST
                )?.trim()?.takeIf { it.isNotEmpty() },
                album = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_ALBUM
                )?.trim()?.takeIf { it.isNotEmpty() },
                durationMs = duration,
                coverBytes = coverBytes
            )
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 从已在私有目录的音频文件取内嵌封面原字节（供旧曲目一次性
     * 回填用，见 [CoverBackfill]）。只读本 App 私有目录的副本，
     * 不碰原始文件；无图或读取失败返回 null，不抛异常。
     * 必须在协程中调用；IO 操作在 Dispatchers.IO 上执行。
     */
    suspend fun extractEmbeddedPicture(audioFile: File): ByteArray? =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(audioFile.absolutePath)
                try {
                    retriever.embeddedPicture
                } catch (_: Exception) {
                    null
                }
            } catch (_: Exception) {
                null
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                }
            }
        }

    private fun readLrcText(uri: Uri): String? {
        return try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                // P0：UTF-8 失败时尝试 GBK（旧 LRC 常为 GBK 编码，
                // 硬编码 UTF-8 会整首乱码）；解码本身永不抛异常。
                LyricsParser.decodeLrcBytes(input.readBytes())
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /**
         * 标题：优先取内嵌元数据，否则取文件名（去扩展名）。
         * 纯函数（TestMusic 覆盖）。
         */
        fun titleFromMetadata(metadataTitle: String?, fileName: String): String {
            if (!metadataTitle.isNullOrBlank()) return metadataTitle.trim()
            return fileName.substringBeforeLast('.', fileName)
                .trim().ifEmpty { "未知曲目" }
        }

        /**
         * 曲库搜索（内存筛选，只处理已有元数据，不扫描或重新加载
         * 音频文件，不建搜索数据库）。字段：歌曲名称、文件名称；
         * 英文不区分大小写；支持中文、日文、英文；空查询返回全部。
         * 纯函数（TestMusic 覆盖）。
         */
        fun filterMusicTracks(tracks: List<MusicTrack>, query: String): List<MusicTrack> {
            val q = query.trim().lowercase(Locale.ROOT)
            if (q.isEmpty()) return tracks
            return tracks.filter { track ->
                track.title.lowercase(Locale.ROOT).contains(q) ||
                    track.fileName.lowercase(Locale.ROOT).contains(q)
            }
        }
    }
}
