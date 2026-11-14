package com.artlog.app.ui.music

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.artlog.app.data.AppearanceSettings
import com.artlog.app.data.CoverBackfill
import com.artlog.app.data.MusicImport
import com.artlog.app.data.MusicTrack
import com.artlog.app.playback.MusicPlaybackState
import com.artlog.app.playback.MusicRepeatMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 音乐播放器 ViewModel（P0 全局歌词轮：瘦身为转发层）。
 *
 * 播放状态与歌词状态全部由 App 作用域的 [MusicPlaybackState]
 * 持有：离页不再清除状态、不再释放 controller、不再停轮询。
 * 本类只保留页面逻辑：曲库搜索、导入 / 追加 LRC / 删除，以及
 * 页内歌词面板开关（内存态，默认开；见 [togglePanelLyrics]）。
 *
 * 开关语义（用户决策 1）：
 * - `lyrics_enabled` 是歌词总开关（默认 false）：关闭则所有
 *   环境（页内面板、悬浮歌词）都不显示。
 * - 页内“歌词：开/关”按钮改为页内歌词面板开关：只控制
 *   [LyricsView] 面板显隐，不动悬浮歌词；面板打开而总开关
 *   关闭时，一并打开总开关（否则点按无可见变化）。
 * - 内部悬浮开关（floating_lyrics_enabled，默认 false）独立：
 *   总开关开 + 内部开才在 Artlog 页面显示悬浮歌词。
 */
class MusicViewModel(
    private val holder: MusicPlaybackState,
    private val musicImport: MusicImport,
    private val appearanceSettings: AppearanceSettings,
    /** 最终完善轮：旧曲目封面一次性回填（可空；无则不回填）。 */
    private val coverBackfill: CoverBackfill? = null
) : ViewModel() {

    /**
     * 升级后首次进入音乐流程：后台对 coverPath 为空的旧曲目跑一
     * 次封面回填（[CoverBackfill.runOnce]，标志位保证只跑一次，
     * 不阻塞 UI、不向用户报错；失败留 null，下次不再重试）。
     */
    init {
        viewModelScope.launch {
            try {
                coverBackfill?.runOnce()
            } catch (_: Exception) {
                // 回填失败不阻塞音乐页；标志未置，下次进页再跑。
            }
        }
    }

    /** 全部曲目（按导入时间倒序；来自持有者）。 */
    val tracks: StateFlow<List<MusicTrack>> = holder.tracks

    /** 歌词总开关（持久化，默认 false）。 */
    val lyricsEnabled: StateFlow<Boolean> = holder.lyricsEnabled

    /** 内部悬浮歌词开关（持久化，默认 false）。 */
    val floatingLyricsEnabled: StateFlow<Boolean> = holder.floatingLyricsEnabled

    /** 播放 / 歌词状态：全部转发持有者（离页不清除）。 */
    val isPlaying: StateFlow<Boolean> = holder.isPlaying
    val currentTrackId: StateFlow<String?> = holder.currentTrackId
    val currentTrack: StateFlow<MusicTrack?> = holder.currentTrack
    val positionMs: StateFlow<Long> = holder.positionMs
    val durationMs: StateFlow<Long> = holder.durationMs
    val volume: StateFlow<Float> = holder.volume
    val isMuted: StateFlow<Boolean> = holder.isMuted
    val repeatMode: StateFlow<MusicRepeatMode> = holder.repeatMode
    /** 随机开关（持久化，默认 false；洗牌序列在持有者内、内存态）。 */
    val shuffleEnabled: StateFlow<Boolean> = holder.shuffleEnabled
    val parsedLyrics = holder.parsedLyrics
    val currentLyricIndex: StateFlow<Int> = holder.currentLyricIndex
    val lyricsError = holder.lyricsError
    val statusMessage: StateFlow<String?> = holder.statusMessage

    fun clearStatus() = holder.clearStatus()

    // ---- 页内歌词面板开关（内存态，不持久化） ----

    private val _panelLyricsEnabled = MutableStateFlow(true)
    val panelLyricsEnabled: StateFlow<Boolean> = _panelLyricsEnabled.asStateFlow()

    /**
     * 页内歌词面板开关：只控制页内 LyricsView 面板显隐。
     * 面板打开而总开关关闭时，一并打开总开关并恢复当前歌词，
     * 否则点按后面板仍不出现；面板关闭不影响悬浮歌词与播放。
     */
    fun togglePanelLyrics() {
        val next = !_panelLyricsEnabled.value
        _panelLyricsEnabled.value = next
        if (next && !lyricsEnabled.value) {
            appearanceSettings.setLyricsEnabled(true)
            holder.loadLyricsForTrack(currentTrackId.value)
        }
    }

    /** 内部悬浮歌词开关（持久化；总开关关闭时悬浮仍不显示）。 */
    fun toggleFloatingLyricsEnabled() {
        appearanceSettings.setFloatingLyricsEnabled(!floatingLyricsEnabled.value)
    }

    // ---- 搜索（只筛已有元数据，不扫描/重载音频文件） ----

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** 曲目数 > 20 时显示搜索入口；≤ 20 时隐藏（回到 ≤20 时可再隐藏）。 */
    val showSearch: StateFlow<Boolean> =
        tracks.map { it.size > 20 }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 当前筛选后的列表（空查询 = 完整列表）。 */
    val filteredTracks: StateFlow<List<MusicTrack>> =
        combine(tracks, _searchQuery) { list, query ->
            MusicImport.filterMusicTracks(list, query)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun clearSearch() {
        _searchQuery.value = ""
    }

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    // ---- 播放控制（全部转发持有者） ----

    fun play(track: MusicTrack) = holder.play(track)
    fun togglePlayPause() = holder.togglePlayPause()
    fun playNext() = holder.playNext()
    fun playPrevious() = holder.playPrevious()
    fun seekTo(positionMs: Long) = holder.seekTo(positionMs)
    fun setVolume(value: Float) = holder.setVolume(value)
    fun toggleMute() = holder.toggleMute()
    fun toggleRepeatMode() = holder.toggleRepeatMode()
    fun toggleShuffle() = holder.toggleShuffle()

    // ---- 导入 / 追加 LRC / 删除 ----

    /** SAF 多选导入（音频 + 同名 LRC 一并处理）。 */
    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty() || _isImporting.value) return
        _isImporting.value = true
        viewModelScope.launch {
            try {
                val result = musicImport.importTracks(uris)
                val parts = mutableListOf<String>()
                if (result.imported.isNotEmpty()) parts.add("已导入 ${result.imported.size} 首")
                if (result.failed.isNotEmpty()) {
                    parts.add("失败 ${result.failed.size} 首")
                    holder.setStatusMessage((parts.joinToString("，") + "：") +
                        result.failed.joinToString("；") { (name, reason) -> "$name（$reason）" })
                } else {
                    holder.setStatusMessage(parts.joinToString("，"))
                }
            } catch (e: Exception) {
                holder.setStatusMessage("导入失败：${e.message ?: "未知错误"}")
            } finally {
                _isImporting.value = false
            }
        }
    }

    /** 曲目详情内单独追加 LRC（用户确认后复制到私有目录）。 */
    fun attachLyrics(track: MusicTrack, lrcUri: Uri) {
        viewModelScope.launch {
            try {
                musicImport.attachLyrics(track, lrcUri)
                holder.setStatusMessage("已关联歌词：${track.title}")
                // 同一曲目歌词文本已变：缓存失效，强制重解析。
                if (track.id == currentTrackId.value) holder.reloadLyricsForTrack(track.id)
            } catch (e: Exception) {
                holder.setStatusMessage("关联歌词失败：${e.message ?: "未知错误"}")
            }
        }
    }

    /**
     * 删除曲目（二次确认由 UI 做）。删当前播放歌曲时先按当前模式
     * 算出下一首与续播队列（清状态前算，否则队列已空、无从得知），
     * 再安全停止、删文件与行；删成功且有下一首时按当前模式续播
     * （列表循环回绕 / 随机按洗牌序列下一首 / 播完一轮即停等，
     * 见 PlaybackModeLogic.nextAfterDeletion），删空或无定义下一首
     * 时停播并显示空状态。删其他曲目时如其在队列中一并移除队列项。
     * 删文件失败时抛错、行保留，由 [MusicImport.deleteTrack] 保证；
     * 既有限制：删文件失败时播放状态已被清空且无法恢复（与旧行为
     * 一致，本轮未改删除顺序——先停播再删文件，避免播放中删文件）。
     */
    fun deleteTrack(track: MusicTrack) {
        viewModelScope.launch {
            val isCurrent = track.id == currentTrackId.value
            // 删当前歌：清状态前先算续播计划。
            val plan = if (isCurrent) holder.planAfterDeletingCurrent(track.id) else null
            try {
                if (isCurrent) {
                    holder.clearCurrentTrackState()
                } else {
                    holder.removeQueueItem(track.id)
                }
                musicImport.deleteTrack(track)
                if (isCurrent && plan?.nextTrack != null) {
                    holder.playOrdered(plan.nextTrack, plan.queue)
                }
                holder.setStatusMessage("已删除：${track.title}")
            } catch (e: Exception) {
                holder.setStatusMessage("删除失败：${e.message ?: "未知错误"}")
            }
        }
    }
}

/** 毫秒时长格式化为 m:ss（纯函数）。 */
fun formatMusicDuration(durationMs: Long?): String {
    if (durationMs == null || durationMs <= 0) return "--:--"
    val totalSeconds = durationMs / 1000
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}
