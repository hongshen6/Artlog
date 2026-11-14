package com.artlog.app.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 旧曲目封面一次性后台回填（最终完善轮，数据库 v7）。
 *
 * 触发：升级后首次进入音乐流程时（[MusicViewModel] 初始化时发
 * 起，后台跑，不阻塞 UI）。只处理 `coverPath == null` 的已有曲
 * 目：逐首从私有目录的音频副本取内嵌图（[MusicImport] 的
 * Retriever 路径），成功写盘并更新字段，失败留 null。
 *
 * 只跑一次的保证：`cover_backfill_done` 标志位（SharedPreferences
 * `artlog_appearance`，见 [AppearanceSettings]）。选择标志位而
 * 不是“每次进音乐页都扫描”的理由：
 * - 幂等：选择逻辑本身就是幂等的（只处理 coverPath 为空的曲
 *   目），标志位只是避免每次进页都重复开 Retriever 扫全库；
 * - 恢复一致性：封面文件进备份、标志位随 settings.json 一同恢
 *   复，v7 备份恢复后两者一致；v6 备份恢复后标志缺席即 false，
 *   回填会再跑一次（仍幂等）；
 * - 失败不卡死：标志在整轮跑完后才置 true（无论个别曲目成败，
 *   失败的曲目留 null，下次不再重试——反复重试无内嵌图的曲目
 *   没有意义）；若中途崩溃 / 进程被杀，标志未置，下次进音乐页
 *   会再跑一次（仍只处理 coverPath 为空的曲目）。
 *
 * 本类不碰原始文件、不联网、不扫描媒体库；提取在 IO 线程。
 */
class CoverBackfill(
    context: Context,
    private val repository: MusicTrackRepository,
    private val musicImport: MusicImport,
    private val appearanceSettings: AppearanceSettings
) {
    private val appContext = context.applicationContext

    /** 一次回填的结果（供调用方记日志 / 报状态，不向用户弹窗）。 */
    sealed interface BackfillResult {
        /** 标志已是 true，本次什么都没做。 */
        data object AlreadyDone : BackfillResult

        /** 跑完一轮：待处理数 / 成功写盘数 / 失败留 null 数。 */
        data class Ran(
            val pending: Int,
            val filled: Int,
            val failed: Int
        ) : BackfillResult
    }

    /**
     * 跑一次回填（标志已是 true 时直接返回 [BackfillResult.AlreadyDone]）。
     * 必须在协程中调用；全部 IO 在 Dispatchers.IO 上执行。
     */
    suspend fun runOnce(): BackfillResult = withContext(Dispatchers.IO) {
        if (appearanceSettings.isCoverBackfillDone()) {
            return@withContext BackfillResult.AlreadyDone
        }
        val pending = AlbumCoverLogic.selectForBackfill(
            repository.observeTracks().first()
        )
        var filled = 0
        var failed = 0
        for (track in pending) {
            try {
                val audioFile = File(track.filePath)
                if (!audioFile.isFile) {
                    failed++
                    continue
                }
                val bytes = musicImport.extractEmbeddedPicture(audioFile)
                val coverFile = MusicStorage.saveCover(appContext, track.id, bytes)
                if (coverFile != null) {
                    repository.updateTrack(track.copy(coverPath = coverFile.absolutePath))
                    filled++
                } else {
                    // 无内嵌图 / 字节头无法识别 / 写盘失败：留 null。
                    failed++
                }
            } catch (_: Exception) {
                failed++
            }
        }
        // 整轮跑完才置标志（无论个别成败）；中途崩溃则下次再跑。
        appearanceSettings.markCoverBackfillDone()
        BackfillResult.Ran(pending = pending.size, filled = filled, failed = failed)
    }
}
