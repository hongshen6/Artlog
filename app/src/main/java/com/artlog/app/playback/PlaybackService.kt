package com.artlog.app.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.artlog.app.data.AlbumCoverLogic
import com.artlog.app.data.MusicTrack
import java.io.File

/**
 * 音乐播放服务（下一阶段迭代，阶段 6；P0 全局歌词轮加固）。
 *
 * `MediaSessionService` 子类 + ExoPlayer + MediaSession。通知栏/
 * 锁屏控制由 Media3 的默认通知提供者生成（标题/艺术家取曲目元
 * 数据），App 不手写通知。
 *
 * - 播放队列 = 当前曲库列表顺序或洗牌序列（由 [MusicPlaybackState] 经
 *   MediaController 下发 MediaItem 列表）；循环三档（顺序 / 列表循环 /
 *   单曲循环，列表循环用 ExoPlayer 原生 REPEAT_MODE_ALL）与随机开关
 *   （应用层洗牌序列，不用 ExoPlayer 内置随机）由 P1 接线，决策逻辑
 *   在 [PlaybackModeLogic]（纯函数，JVM 可测）。
 * - App 被杀后 Service 随系统回收，不做播放状态持久化、不自动
 *   续播（用户决策 2）；下次打开从曲库重新选歌。
 * - 音量只影响本播放器（ExoPlayer 音量），不改系统媒体音量。
 * - 音频焦点（P0 补实现）：播放开始时申请焦点；LOSS / 短暂丢失
 *   时暂停并记下状态，获得焦点后按原状态恢复；可 duck 时只压低
 *   音量（原音量的 30%）。决策逻辑在 [AudioFocusPolicy]（纯函数，
 *   JVM 可测），本类只执行决策。
 * - 耳机拔出（P0 补实现）：注册 ACTION_AUDIO_BECOMING_NOISY，
 *   拔出时暂停；接收器与服务生命周期对齐（onCreate 注册、
 *   onDestroy 注销），不泄漏。
 * - 封面图（数据库 v7）：曲目有 `coverPath` 时 mediaItemFor()
 *   写 `setArtworkUri(Uri.fromFile(coverFile))`，通知栏 / 锁屏由
 *   Media3 默认通知取用；无封面不写 artwork，不伪造。切歌后元数
 *   据随曲目更新（MediaItem 按曲目重建）。
 *
 * Manifest 声明：`android:foregroundServiceType="mediaPlayback"` +
 * intent-filter `androidx.media3.session.MediaSessionService`。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    /** 是否因焦点丢失而暂停（获得焦点后按此恢复）。 */
    private var pausedByFocusLoss = false

    /** 是否处于 duck 状态（音量被压低）。 */
    private var isDucked = false

    /**
     * 焦点丢失导致的那次暂停：playerListener 收到
     * onIsPlayingChanged(false) 时不放弃焦点——放弃后系统不再
     * 回调 GAIN，pausedByFocusLoss 的恢复就永远等不到。
     * 用户主动暂停 / 播完仍正常放弃焦点。
     */
    private var suppressAbandonOnce = false

    /** duck 前的播放器音量（获得焦点后恢复）。 */
    private var volumeBeforeDuck = 1f

    private val focusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        val p = player ?: return@OnAudioFocusChangeListener
        val outcome = AudioFocusPolicy.onFocusChange(
            focusChange = focusChange,
            wasPlaying = p.isPlaying,
            pausedByFocusLoss = pausedByFocusLoss,
            isDucked = isDucked
        )
        if (outcome.duck) {
            if (!isDucked) volumeBeforeDuck = p.volume
            p.volume = volumeBeforeDuck * AudioFocusPolicy.DUCK_VOLUME_FACTOR
            isDucked = true
        }
        if (outcome.pause) {
            // 先立标记再暂停：紧接着触发的 onIsPlayingChanged(false)
            // 必须跳过放弃焦点，否则收不到后续的 GAIN。
            suppressAbandonOnce = true
            p.pause()
        }
        if (outcome.markPausedByFocusLoss) {
            pausedByFocusLoss = true
        }
        if (outcome.restoreVolume && isDucked) {
            p.volume = volumeBeforeDuck
            isDucked = false
        }
        if (outcome.resume) {
            pausedByFocusLoss = false
            p.play()
        }
    }

    /** 耳机拔出：正在播放才暂停，不做恢复标记。 */
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            val p = player ?: return
            if (AudioFocusPolicy.shouldPauseOnBecomingNoisy(p.isPlaying)) {
                p.pause()
            }
        }
    }

    /** 播放开始时申请焦点；暂停 / 停止时放弃焦点。 */
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                requestFocus()
            } else if (suppressAbandonOnce) {
                // 焦点丢失导致的那次暂停：保留焦点请求，等 GAIN 回调。
                suppressAbandonOnce = false
            } else {
                // 用户主动暂停 / 播完：放弃焦点。
                abandonFocus()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        // 两份 AudioAttributes：ExoPlayer 要 media3 的，系统焦点请求
        // 要 android.media 的；两者语义相同（USAGE_MEDIA / MUSIC）。
        val attributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.CONTENT_TYPE_MUSIC)
            .build()
        val systemAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        // 焦点由本类手动管理（handleAudioFocus=false），以便按
        // AudioFocusPolicy 的语义暂停 / 恢复 / duck；不依赖
        // ExoPlayer 内置焦点处理。
        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(attributes, /* handleAudioFocus= */ false)
            .build()
        exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
        exoPlayer.addListener(playerListener)
        player = exoPlayer
        mediaSession = MediaSession.Builder(this, exoPlayer).build()
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(systemAttributes)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        ContextCompat.registerReceiver(
            this,
            becomingNoisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        try {
            unregisterReceiver(becomingNoisyReceiver)
        } catch (_: Exception) {
            // 服务未完整创建时注销会抛错：忽略，不阻塞销毁。
        }
        abandonFocus()
        player?.removeListener(playerListener)
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        audioManager = null
        focusRequest = null
        super.onDestroy()
    }

    /**
     * 申请音频焦点。申请失败时暂停播放（无焦点不擅自出声）。
     * 注意：onIsPlayingChanged(true) 在 pause() 后也会触发一次
     * false 分支（放弃焦点），与本次申请不冲突。
     */
    private fun requestFocus() {
        val manager = audioManager ?: return
        val request = focusRequest ?: return
        val result = manager.requestAudioFocus(request)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            player?.pause()
        }
    }

    private fun abandonFocus() {
        val manager = audioManager ?: return
        val request = focusRequest ?: return
        manager.abandonAudioFocusRequest(request)
        // 放弃焦点不等于丢失焦点：duck 状态随播放停止而解除，
        // 下次播放从正常音量开始。
        isDucked = false
    }

    companion object {
        /** 由一首曲目构造 MediaItem：URI 为私有目录绝对路径，mediaId 为曲目 id。 */
        fun mediaItemFor(track: MusicTrack): MediaItem {
            val metadataBuilder = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album)
            // 封面（数据库 v7）：有封面才写 artwork URI；无封面不写，
            // 不伪造。Media3 默认通知取用该 URI 渲染通知栏 / 锁屏封面。
            val artworkPath = AlbumCoverLogic.artworkPathFor(track)
            if (artworkPath != null) {
                metadataBuilder.setArtworkUri(Uri.fromFile(File(artworkPath)))
            }
            val metadata = metadataBuilder.build()
            return MediaItem.Builder()
                .setUri(track.filePath)
                .setMediaId(track.id)
                .setMediaMetadata(metadata)
                .build()
        }
    }
}
