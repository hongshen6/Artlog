package com.artlog.app.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artlog.app.data.AppearanceSettings
import com.artlog.app.data.FloatingLyricsLogic
import com.artlog.app.data.ParsedLyrics
import com.artlog.app.playback.MusicPlaybackState
import kotlin.math.roundToInt

/**
 * 内部跨页面悬浮歌词（P0 全局歌词轮；控制在 P2 阶段四完善）。
 *
 * 挂载点：[com.artlog.app.MainActivity] 根 Box 内、Surface 之后，
 * 与背景层同级；全应用只一个实例，覆盖 Home / Gallery / Add /
 * Journal / Statistics / Settings / 视觉灵感全部页面（含 overlay），
 * 页面切换时持续显示，不依赖单个页面维持状态。
 *
 * 数据源：与音乐页共用同一份 [MusicPlaybackState]（parsedLyrics
 * + currentLyricIndex），不另建解析、不另建状态；暂停时停留，
 * 拖动进度后立即更新，切歌后自动更新、不残留上一首歌词。
 *
 * 控制（P2 阶段四）：拖动位置（pointerInput，未锁定时；拖动结束
 * 才持久化 floating_lyrics_x/y）、字号 A− / A＋（步进 2sp，
 * 钳制 10–32sp，接线 floating_lyrics_font_size）、锁定位置
 * （floating_lyrics_locked，锁定后不拖动、按钮仍可用）、上一首 /
 * 下一首 / 播放暂停（直接调现有播放器接口 holder.playPrevious /
 * holder.togglePlayPause / holder.playNext，不建第二播放器）、
 * 收起（关闭 floating_lyrics_enabled）。
 *
 * 触摸策略：歌词卡片本身除按钮与拖动手势外全部透传，不影响页面
 * 滚动、按钮点击、弹窗与输入框；点按（无位移）不被拖动检测消费，
 * 仍透传到底层页面；歌词层不消费系统返回键。
 *
 * 视觉：深色极简；半透明黑底板 + 圆角；当前行加粗高亮、带文字
 * 阴影；上下行弱化显示。默认位置为归一化 (0.5, 0.78)，避开顶部
 * 栏与底部导航。无歌词时显示明确提示。
 */
@Composable
fun FloatingLyricsOverlay(
    holder: MusicPlaybackState,
    appearanceSettings: AppearanceSettings
) {
    val appearance by appearanceSettings.state.collectAsState()
    val currentTrack by holder.currentTrack.collectAsState()
    val isPlaying by holder.isPlaying.collectAsState()
    val parsedLyrics by holder.parsedLyrics.collectAsState()
    val currentLyricIndex by holder.currentLyricIndex.collectAsState()
    val lyricsError by holder.lyricsError.collectAsState()

    val visible = FloatingLyricsLogic.shouldShowFloatingLyrics(
        lyricsEnabled = appearance.lyricsEnabled,
        floatingLyricsEnabled = appearance.floatingLyricsEnabled,
        hasCurrentTrack = currentTrack != null
    )
    if (!visible) return

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        var cardSize by remember { mutableStateOf(IntSize.Zero) }
        val (x, y) = FloatingLyricsLogic.clampPosition(
            appearance.floatingLyricsX, appearance.floatingLyricsY
        )
        val locked = appearance.floatingLyricsLocked
        // 拖动中的归一化位置（内存态）：拖动过程不反复写
        // SharedPreferences，拖动结束才持久化；取消则丢弃。
        var dragPos by remember { mutableStateOf<Pair<Float, Float>?>(null) }
        val (px, py) = dragPos ?: (x to y)
        // 归一化坐标 → 像素偏移（卡片中心对准该点，钳制在屏内）。
        val maxWidthPx = constraints.maxWidth
        val maxHeightPx = constraints.maxHeight
        val xPx = (px * maxWidthPx - cardSize.width / 2f)
            .roundToInt()
            .coerceIn(0, (maxWidthPx - cardSize.width).coerceAtLeast(0))
        val yPx = (py * maxHeightPx - cardSize.height / 2f)
            .roundToInt()
            .coerceIn(0, (maxHeightPx - cardSize.height).coerceAtLeast(0))

        // 拖动：只在未锁定时挂 pointerInput；锁定后卡片恢复
        // P0 的完全透传（除按钮外）。拖动换算见
        // FloatingLyricsLogic.movePosition（纯函数，可 JVM 测试）。
        val dragModifier = if (locked) {
            Modifier
        } else {
            Modifier.pointerInput(locked, x, y, maxWidthPx, maxHeightPx) {
                detectDragGestures(
                    onDragStart = {
                        dragPos = FloatingLyricsLogic.clampPosition(
                            appearance.floatingLyricsX, appearance.floatingLyricsY
                        )
                    },
                    onDragEnd = {
                        dragPos?.let {
                            appearanceSettings.setFloatingLyricsPosition(it.first, it.second)
                        }
                        dragPos = null
                    },
                    onDragCancel = { dragPos = null },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val base = dragPos ?: return@detectDragGestures
                        dragPos = FloatingLyricsLogic.movePosition(
                            base.first, base.second,
                            dragAmount.x, dragAmount.y,
                            maxWidthPx.toFloat(), maxHeightPx.toFloat()
                        )
                    }
                )
            }
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(xPx, yPx) }
                .onSizeChanged { cardSize = it }
                .then(dragModifier)
                .background(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FloatingLyricsBody(
                        parsedLyrics = parsedLyrics,
                        currentLyricIndex = currentLyricIndex,
                        lyricsError = lyricsError,
                        fontSizeSp = appearance.floatingLyricsFontSize,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    // 收起（关闭内部悬浮开关，持久化；
                    // 可在音乐页“悬浮：开”处重新打开）。
                    IconButton(
                        onClick = { appearanceSettings.setFloatingLyricsEnabled(false) },
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "收起悬浮歌词",
                            tint = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
                // 控制行：切歌与播放暂停直接调现有播放器接口，
                // 不建第二播放器；锁定只禁拖动，不禁按钮。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(onClick = { holder.playPrevious() }) {
                        Icon(
                            imageVector = Icons.Filled.SkipPrevious,
                            contentDescription = "上一首",
                            tint = Color.White.copy(alpha = 0.8f)
                        )
                    }
                    IconButton(onClick = { holder.togglePlayPause() }) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause
                                else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "暂停" else "播放",
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = { holder.playNext() }) {
                        Icon(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = "下一首",
                            tint = Color.White.copy(alpha = 0.8f)
                        )
                    }
                    IconButton(
                        onClick = {
                            appearanceSettings.setFloatingLyricsLocked(!locked)
                        }
                    ) {
                        Icon(
                            imageVector = if (locked) Icons.Filled.Lock
                                else Icons.Filled.LockOpen,
                            contentDescription = if (locked) "解锁位置" else "锁定位置",
                            tint = Color.White.copy(alpha = 0.8f)
                        )
                    }
                    IconButton(
                        onClick = {
                            appearanceSettings.setFloatingLyricsFontSize(
                                appearance.floatingLyricsFontSize -
                                    FloatingLyricsLogic.FONT_SIZE_STEP_SP
                            )
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Remove,
                            contentDescription = "减小歌词字号",
                            tint = Color.White.copy(alpha = 0.8f)
                        )
                    }
                    IconButton(
                        onClick = {
                            appearanceSettings.setFloatingLyricsFontSize(
                                appearance.floatingLyricsFontSize +
                                    FloatingLyricsLogic.FONT_SIZE_STEP_SP
                            )
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "增大歌词字号",
                            tint = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 悬浮歌词正文：当前行 + 上下各一行（有时间轴时）；无时间轴时
 * 显示前两行文本；无歌词 / 解析失败时显示明确提示。
 * 暂停时进度不推进，行不切换；拖动进度 / 切歌后下标立即重算。
 * 字号接线 floating_lyrics_font_size（默认 16sp，10–32sp），
 * 弱化行保持 13/16 的比例。
 */
@Composable
private fun FloatingLyricsBody(
    parsedLyrics: ParsedLyrics?,
    currentLyricIndex: Int,
    lyricsError: String?,
    fontSizeSp: Float,
    modifier: Modifier = Modifier
) {
    val shadow = Shadow(
        color = Color.Black.copy(alpha = 0.8f),
        offset = Offset(0f, 1f),
        blurRadius = 4f
    )
    val currentStyle = TextStyle(
        color = Color.White,
        fontSize = fontSizeSp.sp,
        fontWeight = FontWeight.Bold,
        shadow = shadow,
        textAlign = TextAlign.Center
    )
    val dimStyle = TextStyle(
        color = Color.White.copy(alpha = 0.55f),
        fontSize = (fontSizeSp * 13f / 16f).sp,
        fontWeight = FontWeight.Normal,
        shadow = shadow,
        textAlign = TextAlign.Center
    )
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (lyricsError != null) {
            Text(text = lyricsError, style = dimStyle, maxLines = 1)
            return
        }
        if (parsedLyrics == null) {
            Text(text = "当前歌曲暂无歌词", style = dimStyle, maxLines = 1)
            return
        }
        if (!parsedLyrics.hasTimestamps) {
            // 无时间轴：整段展示前两行（不滚动，避免悬浮层过高）。
            parsedLyrics.lines.take(2).forEach { line ->
                Text(
                    text = line.text,
                    style = currentStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (parsedLyrics.lines.isEmpty()) {
                Text(text = "当前歌曲暂无歌词", style = dimStyle, maxLines = 1)
            }
            return
        }
        val timed = parsedLyrics.timedLines
        if (timed.isEmpty()) {
            Text(text = "当前歌曲暂无歌词", style = dimStyle, maxLines = 1)
            return
        }
        // 进度在第一行之前：当前行未到，弱化显示第一行。
        val current = currentLyricIndex.coerceIn(0, timed.size - 1)
        if (current > 0) {
            Text(
                text = timed[current - 1].text,
                style = dimStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = timed[current].text,
            style = if (currentLyricIndex >= 0) currentStyle else dimStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (current + 1 < timed.size) {
            Text(
                text = timed[current + 1].text,
                style = dimStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
