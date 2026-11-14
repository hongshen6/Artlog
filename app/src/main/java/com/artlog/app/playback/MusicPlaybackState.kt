package com.artlog.app.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.artlog.app.data.AppearanceSettings
import com.artlog.app.data.LyricsParser
import com.artlog.app.data.MusicTrack
import com.artlog.app.data.MusicTrackRepository
import com.artlog.app.data.ParsedLyrics
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App 作用域播放状态持有者（P0 全局歌词轮，前置条件）。
 *
 * 由 [com.artlog.app.ArtlogApplication] 持有唯一实例，生命周期
 * 与进程相同：音乐页关闭、页面切换、Activity 重建都不会清除
 * 它，MediaController 不释放、500ms 进度轮询不停、歌词状态不丢。
 * [com.artlog.app.ui.music.MusicViewModel] 只转发它（曲库搜索 /
 * 导入 / 删除等页面逻辑仍在 ViewModel 内）。
 *
 * - 播放本身仍在 [PlaybackService]（ExoPlayer + MediaSession）；
 *   本类只持有控制端（MediaController）与 App 侧状态，不建第
 *   二套播放器。
 * - 歌词解析结果缓存在此（[parsedLyrics] + [lastLyricsTrackId]）：
 *   切歌时解析一次，同步只查下标；同一曲目重复调用不重复解析，
 *   只有歌词内容变化时（追加 LRC）才经 [reloadLyricsForTrack]
 *   强制重解析。
 * - 服务被回收后不自动续播（用户决策 2）：本类不持久化曲目与
 *   进度，连接失败只报状态，不自动恢复播放。
 */
class MusicPlaybackState(
    private val repository: MusicTrackRepository,
    private val appearanceSettings: AppearanceSettings,
    appContext: Context
) {
    private val context = appContext.applicationContext

    /** 进程级作用域：随 Application 存在，不随页面清除。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 全部曲目（按导入时间倒序），供播放队列与歌词解析共用。 */
    val tracks: StateFlow<List<MusicTrack>> =
        repository.observeTracks()
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** 歌词总开关（lyrics_enabled，持久化，默认 false）。 */
    val lyricsEnabled: StateFlow<Boolean> =
        appearanceSettings.state.map { it.lyricsEnabled }
            .stateIn(scope, SharingStarted.Eagerly, false)

    /** 内部悬浮歌词开关（floating_lyrics_enabled，默认 false）。 */
    val floatingLyricsEnabled: StateFlow<Boolean> =
        appearanceSettings.state.map { it.floatingLyricsEnabled }
            .stateIn(scope, SharingStarted.Eagerly, false)

    // ---- 播放状态（经 MediaController 同步） ----

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentTrackId = MutableStateFlow<String?>(null)
    val currentTrackId: StateFlow<String?> = _currentTrackId.asStateFlow()

    /** 当前播放曲目（曲库行；队列外时为 null）。 */
    val currentTrack: StateFlow<MusicTrack?> =
        combine(tracks, _currentTrackId) { list, id ->
            list.firstOrNull { it.id == id }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _repeatMode =
        MutableStateFlow(appearanceSettings.snapshot().repeatMode)
    val repeatMode: StateFlow<MusicRepeatMode> = _repeatMode.asStateFlow()

    /**
     * 随机开关（shuffle_enabled，持久化，默认 false）。
     * 只记开关本身；洗牌序列见 [shuffledOrder]，重启后重新洗牌。
     */
    private val _shuffleEnabled =
        MutableStateFlow(appearanceSettings.snapshot().shuffleEnabled)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    /**
     * 当前洗牌序列（曲目 id 列表，内存态，不持久化、不进数据库）。
     * 第一首为序列起点（开启时为当前歌 / 点选的歌）；为空表示
     * 尚未洗牌（随机开但未播过，或随机已关）。
     */
    private var shuffledOrder: List<String> = emptyList()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    fun clearStatus() {
        _statusMessage.value = null
    }

    /** 供 ViewModel 写入导入 / 删除等页面操作的结果文案（与播放连接状态共用同一条 UI 状态流）。 */
    fun setStatusMessage(text: String?) {
        _statusMessage.value = text
    }

    // ---- 歌词（与音乐页、悬浮歌词共用同一份解析结果） ----

    private val _parsedLyrics = MutableStateFlow<ParsedLyrics?>(null)
    val parsedLyrics: StateFlow<ParsedLyrics?> = _parsedLyrics.asStateFlow()

    /** 当前歌词行下标（按进度匹配最近时间标签；无时间轴/无歌词为 -1）。 */
    val currentLyricIndex: StateFlow<Int> =
        combine(_parsedLyrics, _positionMs) { parsed, position ->
            if (parsed == null || !parsed.hasTimestamps) -1
            else LyricsParser.findCurrentLineIndex(parsed.timedLines, position)
        }.stateIn(scope, SharingStarted.Eagerly, -1)

    /** 歌词读取失败时的提示（不影响播放）。 */
    private val _lyricsError = MutableStateFlow<String?>(null)
    val lyricsError: StateFlow<String?> = _lyricsError.asStateFlow()

    /** 已解析歌词所属的曲目 id（缓存键；切歌时先清空再解析）。 */
    private var lastLyricsTrackId: String? = null

    // ---- MediaController 接线（只连一次，离页不释放） ----

    private var controller: MediaController? = null
    private var controllerFuture: java.util.concurrent.Future<MediaController>? = null
    private var volumeBeforeMute = 1f

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            refreshPosition()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                _isPlaying.value = false
            }
            refreshPosition()
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            // P1：随机 + 列表循环时，ExoPlayer 原生回绕到队首
            // （reason == REPEAT）即一轮播完：重洗下一轮并把队列
            // 换成新顺序。此时新一轮队首刚开始播（进度≈0），换队
            // 首无可感知的跳歌；重下发会再触发一次 transition，
            // reason 为 PLAYLIST_CHANGED，不会递归进本分支。
            // 手动点“下一首”从队尾回绕时 reason 为 SEEK，不重洗，
            // 本轮按原顺序播完、自然回绕时再洗。
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
                _shuffleEnabled.value && _repeatMode.value == MusicRepeatMode.ALL
            ) {
                val newRound = PlaybackModeLogic.reshuffleForNextRound(
                    shuffledOrder, tracks.value.map { it.id }
                )
                if (newRound.isNotEmpty() && newRound != shuffledOrder) {
                    shuffledOrder = newRound
                    reissueQueueAtStart(newRound)
                    return
                }
            }
            _currentTrackId.value = mediaItem?.mediaId
            loadLyricsForTrack(mediaItem?.mediaId)
            refreshPosition()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            refreshPosition()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _repeatMode.value = PlaybackModeLogic.fromPlayerRepeatMode(repeatMode)
        }
    }

    init {
        scope.launch {
            connectWithRetry()
            // 进度轮询：播放中每 500ms 同步一次（歌词高亮与进度条用）。
            // 随持有者存在，不随音乐页关闭而停止。
            while (true) {
                delay(500)
                if (_isPlaying.value) refreshPosition()
            }
        }
    }

    /**
     * 连接播放服务，最多尝试 3 次（每次间隔 1s）。失败只报状态，
     * 不阻塞 App 启动；音乐页打开后用户点播放时会再次提示服务
     * 未就绪。连接成功后 controller 不再释放（进程级持有）。
     */
    private suspend fun connectWithRetry() {
        repeat(3) { attempt ->
            val future = withContext(Dispatchers.Main) {
                MediaController.Builder(
                    context,
                    SessionToken(context, ComponentName(context, PlaybackService::class.java))
                ).buildAsync()
            }
            controllerFuture = future
            try {
                val built = withContext(Dispatchers.IO) { future.get() }
                controller = built
                built.addListener(playerListener)
                _volume.value = built.volume
                // P1：恢复持久化的播放模式（默认 ORDER），写回服务侧
                // ExoPlayer，而不是沿用服务初始的 REPEAT_MODE_OFF；
                // 随机开关已在字段初值恢复，洗牌序列不恢复、首次
                // 播放时以点选歌为起点重洗。
                val savedMode = appearanceSettings.snapshot().repeatMode
                built.repeatMode = PlaybackModeLogic.toPlayerRepeatMode(savedMode)
                _repeatMode.value = savedMode
                refreshPosition()
                return
            } catch (_: ExecutionException) {
                if (attempt == 2) _statusMessage.value = "无法连接播放服务，请重试"
            } catch (_: InterruptedException) {
                if (attempt == 2) _statusMessage.value = "无法连接播放服务，请重试"
            }
            delay(1000)
        }
    }

    private fun refreshPosition() {
        val c = controller ?: return
        _positionMs.value = c.currentPosition.coerceAtLeast(0L)
        val duration = c.duration
        _durationMs.value = if (duration > 0) duration else 0L
        val mediaId = c.currentMediaItem?.mediaId
        if (mediaId != null && mediaId != _currentTrackId.value) {
            _currentTrackId.value = mediaId
            loadLyricsForTrack(mediaId)
        }
    }

    // ---- 播放控制（与旧 MusicViewModel 同语义，只改数据源） ----

    /**
     * 播放指定曲目（队列 = 当前有效顺序；点已在播的曲目不重头开始）。
     * 随机开时以点选歌为起点重洗一轮；随机关时按曲库原顺序建队列。
     * （仍用完整 tracks.value 建队列，不用音乐页的筛选列表——与
     * 旧行为一致，本轮不改。）
     */
    fun play(track: MusicTrack) {
        val c = controller ?: run {
            _statusMessage.value = "播放服务尚未就绪，请稍候再试"
            return
        }
        val list = tracks.value
        if (list.none { it.id == track.id }) return
        if (_currentTrackId.value == track.id && c.mediaItemCount > 0) {
            c.play()
            return
        }
        val orderedTracks = if (_shuffleEnabled.value) {
            shuffledOrder = PlaybackModeLogic.buildShuffleOrder(
                list.map { it.id }, track.id
            )
            orderTracksByIds(list, shuffledOrder)
        } else {
            list
        }
        startPlayback(track, orderedTracks)
    }

    /**
     * 按给定顺序播放指定曲目（删当前歌续播用：队列须是不含已删
     * 歌的续播顺序，不能再用 tracks.value 重建——曲库流可能尚未
     * 排除已删的歌）。
     */
    fun playOrdered(track: MusicTrack, orderedTracks: List<MusicTrack>) {
        val c = controller ?: run {
            _statusMessage.value = "播放服务尚未就绪，请稍候再试"
            return
        }
        if (orderedTracks.none { it.id == track.id }) return
        startPlayback(track, orderedTracks)
    }

    /** 按给定顺序建队列并从指定曲目起播（play / playOrdered 共用）。 */
    private fun startPlayback(track: MusicTrack, orderedTracks: List<MusicTrack>) {
        val c = controller ?: return
        val index = orderedTracks.indexOfFirst { it.id == track.id }
        if (index < 0) return
        val items = orderedTracks.map { PlaybackService.mediaItemFor(it) }
        c.setMediaItems(items, index, 0L)
        c.prepare()
        c.play()
        _currentTrackId.value = track.id
        loadLyricsForTrack(track.id)
    }

    /** 按 id 顺序排列曲目（id 不在曲库中时丢弃）。 */
    private fun orderTracksByIds(
        list: List<MusicTrack>,
        ids: List<String>
    ): List<MusicTrack> {
        val byId = list.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun playNext() {
        val c = controller ?: return
        if (c.hasNextMediaItem()) c.seekToNextMediaItem()
    }

    fun playPrevious() {
        val c = controller ?: return
        if (c.hasPreviousMediaItem()) c.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        // 拖动后立即更新进度，歌词下标随之立即重算（不等待轮询）。
        _positionMs.value = positionMs
    }

    /** 只改本播放器音量（ExoPlayer 音量），不改系统媒体音量。 */
    fun setVolume(value: Float) {
        val v = value.coerceIn(0f, 1f)
        controller?.volume = v
        _volume.value = v
        if (v > 0f) {
            _isMuted.value = false
            volumeBeforeMute = v
        }
    }

    fun toggleMute() {
        val c = controller ?: return
        if (_isMuted.value) {
            val restore = volumeBeforeMute.coerceIn(0.01f, 1f)
            c.volume = restore
            _volume.value = restore
            _isMuted.value = false
        } else {
            volumeBeforeMute = _volume.value.coerceAtLeast(0.01f)
            c.volume = 0f
            _volume.value = 0f
            _isMuted.value = true
        }
    }

    /**
     * 循环三档切换：ORDER → ALL → SINGLE → ORDER。
     * 列表循环用 ExoPlayer 原生 REPEAT_MODE_ALL（播完末首自动回
     * 首首，无需应用层手动回绕）。空列表时无动作、不崩溃。
     * 切换后持久化（repeat_mode）。
     */
    fun toggleRepeatMode() {
        val c = controller ?: return
        if (tracks.value.isEmpty()) return
        val next = PlaybackModeLogic.nextMode(_repeatMode.value)
        c.repeatMode = PlaybackModeLogic.toPlayerRepeatMode(next)
        _repeatMode.value = next
        appearanceSettings.setRepeatMode(next)
    }

    /**
     * 随机开关（独立于循环档位，可组合：随机 + ALL = 随机循环；
     * 随机 + ORDER = 随机播完一轮即停）。
     * 开启时当前歌保留为洗牌序列起点、进度 / 歌词 / 播放状态不
     * 重置；关闭后恢复曲库原顺序，从当前歌在原顺序中的位置继续，
     * 不跳歌、不重头。空列表时无动作、不崩溃；服务未就绪时不改
     * 状态（与 toggleRepeatMode 一致）。切换后持久化
     * （shuffle_enabled），洗牌序列本身不持久化。
     */
    fun toggleShuffle() {
        val c = controller ?: return
        val list = tracks.value
        if (list.isEmpty()) return
        val next = !_shuffleEnabled.value
        if (next) {
            val startId = _currentTrackId.value
                ?.takeIf { id -> list.any { it.id == id } }
                ?: list.first().id
            shuffledOrder = PlaybackModeLogic.buildShuffleOrder(
                list.map { it.id }, startId
            )
        } else {
            shuffledOrder = emptyList()
        }
        _shuffleEnabled.value = next
        appearanceSettings.setShuffleEnabled(next)
        reissueQueuePreservingPosition()
    }

    /**
     * 当前有效队列顺序（id 列表）：随机开时为洗牌序列，否则为
     * 曲库原顺序。供删歌续播等调用方使用。
     */
    fun effectiveOrderIds(): List<String> =
        PlaybackModeLogic.effectiveOrderIds(
            tracks.value.map { it.id }, _shuffleEnabled.value, shuffledOrder
        )

    /** 删当前歌的续播计划（调用方须在清播放状态前调用）。 */
    data class DeleteContinuePlan(
        /** 续播曲目；null = 停播（列表已空 / 删 ORDER 末首 / SINGLE）。 */
        val nextTrack: MusicTrack?,
        /** 续播队列（已不含已删的歌）；停播时为空。 */
        val queue: List<MusicTrack>
    )

    /**
     * 删正在播放的歌时，按当前模式算出下一首与续播队列。
     * 须在 [clearCurrentTrackState] 之前调用（清状态后队列已空、
     * 无从得知）；ALL + 随机且删的是本轮末首时，此处即重洗下一轮
     * （新一轮第一首为续播曲目）。
     */
    fun planAfterDeletingCurrent(deletedId: String): DeleteContinuePlan {
        val order = effectiveOrderIds()
        return when (val decision = PlaybackModeLogic.nextAfterDeletion(
            order, deletedId, _repeatMode.value, _shuffleEnabled.value
        )) {
            PlaybackModeLogic.DeleteNext.Stop -> {
                if (_shuffleEnabled.value) {
                    shuffledOrder = shuffledOrder.filter { it != deletedId }
                }
                DeleteContinuePlan(null, emptyList())
            }
            is PlaybackModeLogic.DeleteNext.Play -> {
                if (_shuffleEnabled.value) shuffledOrder = decision.newOrder
                val queue = orderTracksByIds(tracks.value, decision.newOrder)
                val next = queue.firstOrNull { it.id == decision.nextId }
                DeleteContinuePlan(next, queue)
            }
        }
    }

    /**
     * 按当前有效顺序重下发队列，保持当前歌、进度与播放/暂停状态
     * 不变（随机开关切换时用；当前歌不在新顺序中时从首首 0 秒起）。
     * 未在播（队列为空）时无动作，下次 play() 时按新顺序建队列。
     */
    private fun reissueQueuePreservingPosition() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        val orderedTracks = orderTracksByIds(tracks.value, effectiveOrderIds())
        if (orderedTracks.isEmpty()) return
        val currentId = _currentTrackId.value
        val index = orderedTracks.indexOfFirst { it.id == currentId }
            .takeIf { it >= 0 } ?: 0
        val position =
            if (orderedTracks[index].id == currentId) _positionMs.value else 0L
        val wasPlaying = _isPlaying.value
        c.setMediaItems(
            orderedTracks.map { PlaybackService.mediaItemFor(it) }, index, position
        )
        c.prepare()
        if (wasPlaying) c.play() else c.pause()
    }

    /**
     * 随机 + 列表循环原生回绕时，把队列换成新一轮顺序并从新一轮
     * 队首 0 秒起播（旧一轮队首刚回绕、进度≈0，切换无可感知跳歌）。
     * 调用方随后同步当前曲目与歌词（见 onMediaItemTransition）。
     */
    private fun reissueQueueAtStart(newOrderIds: List<String>) {
        val c = controller ?: return
        val orderedTracks = orderTracksByIds(tracks.value, newOrderIds)
        if (orderedTracks.isEmpty()) return
        val wasPlaying = _isPlaying.value
        c.setMediaItems(
            orderedTracks.map { PlaybackService.mediaItemFor(it) }, 0, 0L
        )
        c.prepare()
        if (wasPlaying) c.play()
        _currentTrackId.value = orderedTracks.first().id
        loadLyricsForTrack(orderedTracks.first().id)
        refreshPosition()
    }

    // ---- 歌词加载（解析结果缓存到本持有者） ----

    /**
     * 为指定曲目加载歌词（解析歌词原文）。切换歌曲时调用：先清空
     * 旧歌词再加载新歌词，不出现上一首残留或串歌；同一曲目重复
     * 调用直接复用已解析结果，不重复解析。歌词读取失败只置
     * [lyricsError] 提示，不影响播放。
     */
    fun loadLyricsForTrack(trackId: String?) {
        if (trackId != null && trackId == lastLyricsTrackId && _parsedLyrics.value != null) {
            return
        }
        _parsedLyrics.value = null
        _lyricsError.value = null
        lastLyricsTrackId = trackId
        if (trackId == null) return
        val track = tracks.value.firstOrNull { it.id == trackId } ?: return
        val text = track.lyricsText
        if (text.isNullOrBlank()) return
        try {
            _parsedLyrics.value = LyricsParser.parseLrc(text)
        } catch (_: Exception) {
            _parsedLyrics.value = null
            _lyricsError.value = "歌词解析失败，仍可正常播放"
        }
    }

    /**
     * 歌词内容变化后强制重解析（如曲目详情内追加 LRC 后，
     * 同一曲目 id 的歌词文本已变，缓存须失效）。
     */
    fun reloadLyricsForTrack(trackId: String?) {
        lastLyricsTrackId = null
        loadLyricsForTrack(trackId)
    }

    /** 删除当前播放曲目时由调用方先清状态（停播、清歌词、清进度）。 */
    fun clearCurrentTrackState() {
        controller?.stop()
        controller?.clearMediaItems()
        _currentTrackId.value = null
        _parsedLyrics.value = null
        _lyricsError.value = null
        lastLyricsTrackId = null
        _isPlaying.value = false
        _positionMs.value = 0L
        _durationMs.value = 0L
    }

    /** 从队列中移除一首非当前播放曲目（删曲目时用；随机序列同步移除）。 */
    fun removeQueueItem(trackId: String) {
        if (_shuffleEnabled.value) {
            shuffledOrder = shuffledOrder.filter { it != trackId }
        }
        val c = controller ?: return
        val queueIndex = (0 until c.mediaItemCount).firstOrNull { i ->
            c.getMediaItemAt(i).mediaId == trackId
        }
        if (queueIndex != null) c.removeMediaItem(queueIndex)
    }

    /**
     * 仅测试 / 进程退出时释放（正常流程不调用：持有者与进程同寿，
     * controller 故意不随页面释放）。调用后 scope 取消，状态流停更。
     */
    fun release() {
        controller?.let {
            it.removeListener(playerListener)
            it.release()
        } ?: controllerFuture?.cancel(true)
        controller = null
        scope.cancel()
    }
}
