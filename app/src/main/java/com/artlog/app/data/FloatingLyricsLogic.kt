package com.artlog.app.data

/**
 * 内部悬浮歌词的纯逻辑（P0 全局歌词轮），另含系统级悬浮歌词
 * （P0 验收修复轮）的纯逻辑：显示判定与待显示行的选取。
 *
 * 不依赖 Android 框架，可在 JVM 上直接测试（TestLyricsP0 /
 * TestExternalLyrics / TestWaterfallLyrics）。只做三件事：位置
 * 归一化坐标的钳制、悬浮歌词是否显示的判定、内部悬浮拖动
 * 与字号步进的纯换算。内部悬浮的拖动 / 大小 / 锁定在 P2
 * 阶段四接线（见 ui/music/FloatingLyrics.kt）；系统级悬浮
 * 的拖动 / 大小 / 锁定在 P0 验收修复轮已实现，走独立的
 * external_* 键，渲染宿主见 playback/ExternalLyricsService。
 *
 * 位置约定：x / y 为归一化坐标（0–1），相对整屏宽高；默认位置
 * 为横向居中（x=0.5）、纵向约 78% 处（y=0.78），即底部导航栏
 * 上方、页面内容下缘附近，不遮挡顶部栏与底部导航。
 *
 * 内部悬浮的拖动 / 大小 / 锁定在 P2 阶段四接线（见
 * ui/music/FloatingLyrics.kt）：拖动换算见 [movePosition]，
 * 字号步进见 [stepFontSize]；系统级悬浮的拖动 / 大小 / 锁定
 * 在 P0 验收修复轮已实现，走独立的 external_* 键，渲染宿主见
 * playback/ExternalLyricsService。
 */
object FloatingLyricsLogic {

    /** 默认横向位置：居中。 */
    const val DEFAULT_X = 0.5f

    /** 默认纵向位置：约 78% 高度处（底部导航上方）。 */
    const val DEFAULT_Y = 0.78f

    /** 默认歌词字号（sp），P2 阶段四接线（悬浮歌词 A− / A＋）。 */
    const val DEFAULT_FONT_SIZE_SP = 16f

    /** 内部悬浮歌词字号范围（sp），与 AppearanceSettings 的钳制一致。 */
    const val FONT_SIZE_MIN_SP = 10f

    /** 内部悬浮歌词字号上限（sp）。 */
    const val FONT_SIZE_MAX_SP = 32f

    /** 字号步进（sp）：悬浮歌词 A− / A＋ 每次调整的幅度。 */
    const val FONT_SIZE_STEP_SP = 2f

    /** 归一化坐标钳制到 [0, 1]（换机/旋转后越界的旧值也被拉回）。 */
    fun clampPosition(x: Float, y: Float): Pair<Float, Float> =
        x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f)

    /** 单个归一化坐标钳制到 [0, 1]。 */
    fun clampCoordinate(value: Float): Float = value.coerceIn(0f, 1f)

    /**
     * 拖动换算：把一次拖动手势的像素位移换算为新的归一化位置。
     *
     * (x, y) 为拖动前的归一化位置，(dxPx, dyPx) 为本次手势的
     * 像素位移，(widthPx, heightPx) 为容器尺寸。结果钳制到
     * [0, 1]；容器尺寸非法（≤ 0）时只做钳制、位置不变。
     * 调用方在拖动结束时才持久化，拖动过程中只持有内存态位置。
     */
    fun movePosition(
        x: Float,
        y: Float,
        dxPx: Float,
        dyPx: Float,
        widthPx: Float,
        heightPx: Float
    ): Pair<Float, Float> {
        if (widthPx <= 0f || heightPx <= 0f) {
            return clampPosition(x, y)
        }
        return clampPosition(x + dxPx / widthPx, y + dyPx / heightPx)
    }

    /** 字号步进：当前字号 ± 步进，钳制到 10–32sp。 */
    fun stepFontSize(currentSp: Float, deltaSp: Float): Float =
        (currentSp + deltaSp).coerceIn(FONT_SIZE_MIN_SP, FONT_SIZE_MAX_SP)

    /**
     * 悬浮歌词是否显示。
     *
     * 三者缺一不可：总开关（lyrics_enabled）开、内部悬浮开关
     * （floating_lyrics_enabled）开、当前有曲目（正在播或已暂停
     * 的曲目都显示，暂停时歌词停留）。页内歌词面板开关不影响
     * 悬浮歌词（两者独立）。
     */
    fun shouldShowFloatingLyrics(
        lyricsEnabled: Boolean,
        floatingLyricsEnabled: Boolean,
        hasCurrentTrack: Boolean
    ): Boolean = lyricsEnabled && floatingLyricsEnabled && hasCurrentTrack

    /**
     * 系统级悬浮歌词是否显示（P0 验收修复轮）。
     *
     * 与内部悬浮同一组三条件，只是第二条件换成系统级开关
     * （external_lyrics_enabled）：总开关（lyrics_enabled）开、
     * 系统级开关开、当前有曲目。权限（SYSTEM_ALERT_WINDOW）
     * 不在这里判定——那是 Android 系统状态，由
     * ExternalLyricsService 在启动与运行中用
     * Settings.canDrawOverlays() 复查。
     */
    fun shouldShowExternalLyrics(
        lyricsEnabled: Boolean,
        externalLyricsEnabled: Boolean,
        hasCurrentTrack: Boolean
    ): Boolean = lyricsEnabled && externalLyricsEnabled && hasCurrentTrack

    /** 系统级悬浮歌词待显示的一行：正文 + 是否为当前行（高亮）。 */
    data class ExternalLyricLine(val text: String, val isCurrent: Boolean)

    /**
     * 系统级悬浮歌词待显示的行（与内部悬浮 FloatingLyricsBody
     * 同一选取规则，只换承载：无歌词 / 解析失败显示明确提示；
     * 无时间轴显示前两行；有时间轴显示当前行及上下各一行，
     * 进度在第一行之前时第一行弱化显示）。
     */
    fun externalDisplayLines(
        parsed: ParsedLyrics?,
        currentLyricIndex: Int,
        lyricsError: String?
    ): List<ExternalLyricLine> {
        if (lyricsError != null) {
            return listOf(ExternalLyricLine(lyricsError, false))
        }
        if (parsed == null) {
            return listOf(ExternalLyricLine("当前歌曲暂无歌词", false))
        }
        if (!parsed.hasTimestamps) {
            val lines = parsed.lines.take(2)
                .map { ExternalLyricLine(it.text, true) }
            return if (lines.isEmpty()) {
                listOf(ExternalLyricLine("当前歌曲暂无歌词", false))
            } else {
                lines
            }
        }
        val timed = parsed.timedLines
        if (timed.isEmpty()) {
            return listOf(ExternalLyricLine("当前歌曲暂无歌词", false))
        }
        val current = currentLyricIndex.coerceIn(0, timed.size - 1)
        val out = mutableListOf<ExternalLyricLine>()
        if (current > 0) out.add(ExternalLyricLine(timed[current - 1].text, false))
        out.add(
            ExternalLyricLine(
                timed[current].text,
                currentLyricIndex >= 0
            )
        )
        if (current + 1 < timed.size) {
            out.add(ExternalLyricLine(timed[current + 1].text, false))
        }
        return out
    }
}
