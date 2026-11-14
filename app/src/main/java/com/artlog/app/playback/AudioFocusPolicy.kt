package com.artlog.app.playback

/**
 * 音频焦点与耳机拔出的纯决策逻辑（P0 全局歌词轮）。
 *
 * 不依赖 Android 框架，可在 JVM 上直接测试（TestLyricsP0）。
 * 数值与 [android.media.AudioManager] 的焦点常量一致
 * （GAIN=1、LOSS=-1、LOSS_TRANSIENT=-2、
 * LOSS_TRANSIENT_CAN_DUCK=-3），但这里不引用 Android 类，
 * 以便纯函数测试；[PlaybackService] 在调用时传入系统常量。
 *
 * 语义：
 * - LOSS / LOSS_TRANSIENT：暂停，并记下“因焦点丢失而暂停”，
 *   以便获得焦点后按原状态恢复；用户主动暂停不进此分支。
 * - LOSS_TRANSIENT_CAN_DUCK：不暂停，只把音量压到 30%，获得
 *   焦点后恢复原音量。
 * - GAIN：若之前因焦点丢失而暂停则恢复播放；若正在 duck 则
 *   恢复音量。
 * - 耳机拔出（ACTION_AUDIO_BECOMING_NOISY）：正在播放才暂停，
 *   不做恢复标记（用户重新插耳机后需手动继续）。
 */
object AudioFocusPolicy {

    const val FOCUS_GAIN = 1
    const val FOCUS_LOSS = -1
    const val FOCUS_LOSS_TRANSIENT = -2
    const val FOCUS_LOSS_TRANSIENT_CAN_DUCK = -3

    /** duck 时的音量系数：压到原音量的 30%。 */
    const val DUCK_VOLUME_FACTOR = 0.3f

    /** 一次焦点变化的决策结果（由调用方执行，本身不碰播放器）。 */
    data class FocusOutcome(
        /** 是否暂停播放。 */
        val pause: Boolean = false,
        /** 是否记下“因焦点丢失而暂停”（供 GAIN 时恢复用）。 */
        val markPausedByFocusLoss: Boolean = false,
        /** 是否进入 duck（压低音量）。 */
        val duck: Boolean = false,
        /** 是否恢复播放（仅当之前因焦点丢失而暂停时）。 */
        val resume: Boolean = false,
        /** 是否恢复 duck 前的音量。 */
        val restoreVolume: Boolean = false
    )

    /**
     * 按焦点变化计算决策。
     *
     * @param focusChange 系统焦点变化值（见上方常量）。
     * @param wasPlaying 变化发生时是否正在播放。
     * @param pausedByFocusLoss 之前是否因焦点丢失而暂停。
     * @param isDucked 当前是否处于 duck 状态。
     */
    fun onFocusChange(
        focusChange: Int,
        wasPlaying: Boolean,
        pausedByFocusLoss: Boolean,
        isDucked: Boolean
    ): FocusOutcome = when (focusChange) {
        FOCUS_LOSS, FOCUS_LOSS_TRANSIENT -> FocusOutcome(
            pause = wasPlaying,
            markPausedByFocusLoss = wasPlaying
        )
        FOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusOutcome(
            duck = wasPlaying || isDucked
        )
        FOCUS_GAIN -> FocusOutcome(
            resume = pausedByFocusLoss,
            restoreVolume = isDucked
        )
        else -> FocusOutcome()
    }

    /** 耳机拔出时是否应暂停：只在正在播放时暂停。 */
    fun shouldPauseOnBecomingNoisy(isPlaying: Boolean): Boolean = isPlaying
}
