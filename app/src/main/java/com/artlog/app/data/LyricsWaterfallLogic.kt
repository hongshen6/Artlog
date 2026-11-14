package com.artlog.app.data

import kotlin.math.abs

/**
 * 瀑布歌词的纯逻辑（P2 阶段四）。
 *
 * 不依赖 Android 框架，可在 JVM 上直接测试
 * （TestWaterfallLyrics）。只做两件纯计算的事：
 * 行透明度（按与当前行的距离算 alpha 渐变）与滚动方式判定
 * （相邻行推进用平滑滚动，大幅跳变直接定位）。
 *
 * 渲染层见 ui/music/MusicScreen.kt 的 LyricsView：颜色仍从
 * MaterialTheme.colorScheme 派生（当前行 onSurface、其余
 * onSurfaceVariant + 本文件的距离 alpha），字号取
 * `lyrics_font_size` 键（默认 18sp，范围 10–32，相邻行按 0.875
 * 比例缩小）；数据层（parsedLyrics /
 * currentLyricIndex）不动，不另建歌词状态源。
 */
object LyricsWaterfallLogic {

    /** 大幅跳变阈值（行）：新旧下标之差超过该值时直接定位。 */
    const val LARGE_JUMP_THRESHOLD = 3

    /**
     * 行透明度：按行与当前行的距离算 alpha 渐变。
     *
     * - 距离 0（当前行）：1.0，不透明、高亮。
     * - 距离 1：0.75；距离 2：0.55；距离 3：0.40。
     * - 距离 ≥ 4：0.30，不再继续变淡，保证远行仍可读。
     * 负距离按绝对值处理（调用方传 abs 后的值亦可）。
     */
    fun rowAlpha(distance: Int): Float {
        return when (abs(distance)) {
            0 -> 1f
            1 -> 0.75f
            2 -> 0.55f
            3 -> 0.4f
            else -> 0.3f
        }
    }

    /**
     * 是否大幅跳变：上一次下标未知（首次定位 / 刚切歌）或
     * 新旧下标之差超过 [threshold] 时返回 true，调用方用
     * scrollToItem 直接定位；否则用 animateScrollToItem 平滑滚动。
     * 拖进度条从开头跳到结尾、切歌后首行定位都走直接定位，
     * 避免平滑滚动经过中间所有行、耗时过长。
     */
    fun isLargeLyricJump(
        previousIndex: Int,
        newIndex: Int,
        threshold: Int = LARGE_JUMP_THRESHOLD
    ): Boolean {
        if (previousIndex < 0) return true
        return abs(newIndex - previousIndex) > threshold
    }
}
