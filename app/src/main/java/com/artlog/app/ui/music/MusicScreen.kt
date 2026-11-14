package com.artlog.app.ui.music

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.artlog.app.data.AppearanceLogic
import com.artlog.app.data.AppearanceSettings
import com.artlog.app.data.LyricsWaterfallLogic
import com.artlog.app.data.MusicTrack
import com.artlog.app.data.ParsedLyrics
import com.artlog.app.playback.MusicRepeatMode
import com.artlog.app.playback.PlaybackModeLogic
import com.artlog.app.ui.ArtlogViewModelFactory
import com.artlog.app.ui.theme.UnscaledDensity
import java.io.File
import kotlin.math.abs

/**
 * 音乐播放页（P2 阶段四重设计）。覆盖层页面，与视觉灵感
 * 同款入口模式（Home 卡片 → 覆盖层，不动 5 个底部导航）。
 *
 * - 播放器主区：封面（数据库 v7：有 coverPath 时 Coil 加载真实
 *   封面文件，无封面或加载失败时显示默认占位图标，不画假封面；
 *   与阶段二首页“正在进行”卡片一致的默认占位图标）、歌曲信息、
 *   进度、瀑布歌词（独立固定区域，位于进度条与播放控制之间，
 *   不随歌曲列表的展开/收起变化）、上一首 / 播放暂停 / 下一首、
 *   播放模式（P1 三档 + 随机，接线原样保留）、音量、歌词与悬浮
 *   开关、展开 / 收起列表。
 * - 展开列表后：搜索（曲目 > 20 首时显示）、播放列表；列表开关
 *   只管列表，歌词的显示只受总开关与页内面板开关控制。
 * - 导入：SAF 多选（音频 + 同名 LRC 一并处理）；不自动扫描媒体库、
 *   不在线搜索、不导入 URL、不自动下载。
 * - 删除：二次确认；只删 App 私有目录中的副本，不删原始文件；
 *   同步删除关联歌词文件；删当前播放歌曲时按当前播放模式续播。
 * - 播放不依赖音乐页面生命周期：状态由 App 作用域持有者持有，
 *   离页继续播；MediaSession、后台播放、音频焦点、耳机拔出、
 *   通知栏与锁屏媒体控制均不在本文件改动。
 */
@Composable
fun MusicScreen(
    onBackClick: () -> Unit,
    viewModelFactory: ArtlogViewModelFactory
) {
    val viewModel: MusicViewModel = viewModel(factory = viewModelFactory)
    val context = LocalContext.current
    val tracks by viewModel.tracks.collectAsState()
    val filteredTracks by viewModel.filteredTracks.collectAsState()
    val showSearch by viewModel.showSearch.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val currentTrack by viewModel.currentTrack.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val positionMs by viewModel.positionMs.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val volume by viewModel.volume.collectAsState()
    val isMuted by viewModel.isMuted.collectAsState()
    val repeatMode by viewModel.repeatMode.collectAsState()
    val shuffleEnabled by viewModel.shuffleEnabled.collectAsState()
    val lyricsEnabled by viewModel.lyricsEnabled.collectAsState()
    val panelLyricsEnabled by viewModel.panelLyricsEnabled.collectAsState()
    val floatingLyricsEnabled by viewModel.floatingLyricsEnabled.collectAsState()
    val parsedLyrics by viewModel.parsedLyrics.collectAsState()
    val currentLyricIndex by viewModel.currentLyricIndex.collectAsState()
    val lyricsError by viewModel.lyricsError.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    // P2 stage 5: the in-page waterfall lyrics have their own font-size
    // key (`lyrics_font_size`) and must NOT follow the global text
    // scale — LyricsView renders inside UnscaledDensity below.
    val appearanceSettings = remember { AppearanceSettings(context) }
    val appearanceState by appearanceSettings.state.collectAsState()

    // 最终验收修改轮：列表开关只管歌曲列表（及搜索入口），默认
    // 收起；瀑布歌词不再共用这个开关，显示只受总开关与页内面板
    // 开关控制（见 MusicPlayerHero 的 showLyrics）。
    var listExpanded by remember { mutableStateOf(false) }
    var detailTrack by remember { mutableStateOf<MusicTrack?>(null) }
    var deleteTarget by remember { mutableStateOf<MusicTrack?>(null) }

    // 导入：SAF 多选，音频 + LRC（text/plain）一起选时按同名配对。
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.importUris(uris)
    }
    // LRC 追加入口：曲目详情内单独选 LRC 文件。
    val lrcLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val track = detailTrack
        if (uri != null && track != null) viewModel.attachLyrics(track, uri)
    }

    // POST_NOTIFICATIONS：首次打开播放器时申请；拒绝则无通知、
    // 播放继续（不阻塞）。
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒绝也无妨：播放页内控制完整可用 */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        // 顶栏：返回 / 标题 / 导入。
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "音乐",
                style = MaterialTheme.typography.titleLarge,
                fontSize = 22.sp
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = { importLauncher.launch(arrayOf("audio/*", "text/plain")) },
                enabled = !isImporting
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "导入音乐",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (statusMessage != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = statusMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { viewModel.clearStatus() }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭提示",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (tracks.isEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "还没有音乐",
                style = MaterialTheme.typography.titleMedium,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "点右上角的 + ，从手机里选音频文件导入（MP3、M4A、AAC、FLAC、WAV、OGG）。" +
                    "文件会复制到 App 私有目录，原文件不会被移动或删除；同名 LRC 文件一起选时会一并导入。",
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // 页面主体用单个 LazyColumn：播放器主区（含独立固定
            // 的瀑布歌词区，位于进度条与播放控制之间）与歌曲列表
            // 依次排布，整体可滚动；展开后列表不再与播放器主区
            // 抢固定高度（旧结构用 weight(1f) 时长列表在小屏上
            // 会被压到 0 高）。歌词不在此外层 LazyColumn 里占
            // item，而是 MusicPlayerHero 内部的固定高度块，只受
            // 总开关与页内面板开关控制——列表展开/收起、行数变
            // 化都不会改变歌词的位置、大小与显示状态。
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ---- 播放器主区（P2 阶段四重设计，最终验收修改轮
                // 调整了内部纵向层级：封面 → 音乐信息 → 进度条 →
                // 瀑布歌词 → 播放控制 → 播放模式 → 音量 → 开关行） ----
                item {
                    MusicPlayerHero(
                        track = currentTrack,
                        isPlaying = isPlaying,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        volume = volume,
                        isMuted = isMuted,
                        repeatMode = repeatMode,
                        shuffleEnabled = shuffleEnabled,
                        lyricsEnabled = panelLyricsEnabled,
                        floatingLyricsEnabled = floatingLyricsEnabled,
                        showLyrics = lyricsEnabled && panelLyricsEnabled,
                        parsedLyrics = parsedLyrics,
                        currentLyricIndex = currentLyricIndex,
                        lyricsError = lyricsError,
                        lyricsFontSize = appearanceState.lyricsFontSize,
                        listExpanded = listExpanded,
                        onTogglePlayPause = { viewModel.togglePlayPause() },
                        onNext = { viewModel.playNext() },
                        onPrevious = { viewModel.playPrevious() },
                        onSeek = { viewModel.seekTo(it) },
                        onToggleLyrics = { viewModel.togglePanelLyrics() },
                        onToggleFloatingLyrics = { viewModel.toggleFloatingLyricsEnabled() },
                        onToggleExpand = { listExpanded = !listExpanded },
                        onToggleMute = { viewModel.toggleMute() },
                        onVolumeChange = { viewModel.setVolume(it) },
                        onToggleRepeatMode = { viewModel.toggleRepeatMode() },
                        onToggleShuffle = { viewModel.toggleShuffle() }
                    )
                }

                // 歌曲列表（独立展开/收起）：只受 listExpanded 控制，
                // 与瀑布歌词无关。搜索入口与列表一体，随列表同显同隐。
                if (listExpanded) {
                    // 搜索：曲目 > 20 首时显示；≤ 20 首时隐藏。
                    if (showSearch) {
                        item {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { viewModel.setSearchQuery(it) },
                                label = { Text(text = "搜索歌曲名称或文件名") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Search,
                                        contentDescription = null
                                    )
                                },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { viewModel.clearSearch() }) {
                                            Icon(
                                                imageVector = Icons.Filled.Close,
                                                contentDescription = "清空搜索"
                                            )
                                        }
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "播放列表",
                                style = MaterialTheme.typography.titleMedium,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "共 ${filteredTracks.size} 首",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (filteredTracks.isEmpty() && searchQuery.isNotBlank()) {
                        item {
                            Text(
                                text = "没有找到匹配的歌曲",
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(filteredTracks, key = { it.id }) { track ->
                            TrackRow(
                                track = track,
                                isCurrent = track.id == currentTrack?.id,
                                isPlaying = isPlaying && track.id == currentTrack?.id,
                                onClick = { viewModel.play(track) },
                                onMoreClick = { detailTrack = track }
                            )
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }

    // 曲目详情：追加 LRC / 删除入口。
    val detail = detailTrack
    if (detail != null) {
        AlertDialog(
            onDismissRequest = { detailTrack = null },
            title = { Text(text = detail.title) },
            text = {
                Column {
                    Text(
                        text = listOfNotNull(
                            detail.artist,
                            detail.album,
                            formatMusicDuration(detail.durationMs)
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "文件：${detail.fileName}",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = when (detail.lyricsSource) {
                            "LRC" -> "歌词：LRC 文件"
                            "EMBEDDED" -> "歌词：内嵌歌词"
                            else -> "歌词：无"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { detailTrack = null }) {
                    Text(text = "关闭")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = { lrcLauncher.launch(arrayOf("*/*")) }
                    ) {
                        Text(text = "追加 LRC")
                    }
                    TextButton(
                        onClick = {
                            deleteTarget = detail
                            detailTrack = null
                        }
                    ) {
                        Text(text = "删除")
                    }
                }
            }
        )
    }

    // 删除二次确认。
    val toDelete = deleteTarget
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(text = "删除这首音乐？") },
            text = {
                Text(
                    text = "将删除「${toDelete.title}」的 App 内副本" +
                        "（音频与关联歌词），原始文件不受影响。" +
                        "正在播放这首歌时，将按当前播放模式继续播下一首，" +
                        "列表播完或单曲循环时则停止播放。",
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteTrack(toDelete)
                        deleteTarget = null
                    }
                ) {
                    Text(text = "删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(text = "取消")
                }
            }
        )
    }
}

/** 播放器主区（P2 阶段四重设计，最终验收修改轮调整了内部纵向
 * 层级：封面 180dp → 歌曲信息 → 进度条 → 瀑布歌词（独立固定区
 * 320dp，位于进度条与播放控制之间，不随歌曲列表的展开/收起变
 * 化）→ 播放控制 → 播放模式 → 音量 → 歌词与悬浮开关、展开 /
 * 收起列表。
 *
 * - 封面（数据库 v7）：有 coverPath 时 Coil 异步加载真实封面
 *   文件，无封面或加载失败时显示默认占位图标（MusicNote），不画
 *   假封面；切歌时封面跟随当前曲目更新（model 即当前曲目的
 *   coverPath，旧图不残留；Coil 内存缓存 + 同一占位兜底，避免
 *   明显闪烁）。
 * - 瀑布歌词：只受总开关与页内面板开关控制（showLyrics），与
 *   列表开关无关；居中 + 平滑滚动 + 行级过渡动画，只改渲染层
 *   的位置与尺寸，动画逻辑（LyricsView 内）原样不动。
 * - 播放模式：P1 接线原样保留（toggleRepeatMode / toggleShuffle），
 *   只改位置与样式——主播放区常显，收起态也能看到当前模式。
 * - 播放不依赖音乐页面生命周期：本文件只发调用，不持有状态。
 */
@Composable
private fun MusicPlayerHero(
    track: MusicTrack?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    volume: Float,
    isMuted: Boolean,
    repeatMode: MusicRepeatMode,
    shuffleEnabled: Boolean,
    lyricsEnabled: Boolean,
    floatingLyricsEnabled: Boolean,
    showLyrics: Boolean,
    parsedLyrics: ParsedLyrics?,
    currentLyricIndex: Int,
    lyricsError: String?,
    lyricsFontSize: Float,
    listExpanded: Boolean,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleLyrics: () -> Unit,
    onToggleFloatingLyrics: () -> Unit,
    onToggleExpand: () -> Unit,
    onToggleMute: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleRepeatMode: () -> Unit,
    onToggleShuffle: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 封面（数据库 v7）：有封面加载真实图，否则占位图。
        // 占位图标垫在 AsyncImage 下层：加载失败时 AsyncImage 不
        // 画任何内容，占位自然露出；切歌时 model 换成新曲目的
        // coverPath，旧图不残留。最终验收修改轮：120dp → 180dp；
        // v26：180dp → 220dp，占位图标按原比例 72dp → 88dp；
        // ContentScale.Crop 保留，比例不变不拉伸。
        CoverImageOrPlaceholder(
            coverPath = track?.coverPath,
            sizeDp = 220.dp,
            cornerDp = 12.dp,
            iconSizeDp = 88.dp,
            contentDescription = track?.title
        )
        Spacer(modifier = Modifier.height(12.dp))
        // 歌曲信息：大字歌名 + 艺术家 · 专辑。
        Text(
            text = track?.title ?: "未在播放",
            style = MaterialTheme.typography.titleLarge,
            fontSize = 20.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(4.dp))
        val artistLine = track?.let {
            listOfNotNull(
                it.artist?.takeIf { a -> a.isNotBlank() },
                it.album?.takeIf { a -> a.isNotBlank() }
            ).joinToString(" · ")
        }.orEmpty()
        Text(
            text = if (track == null) "从下方列表选一首开始播放"
                else artistLine.ifBlank { "未知艺术家" },
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        // 进度条与进度信息。
        val duration = durationMs.coerceAtLeast(0L)
        Slider(
            value = if (duration > 0) positionMs.toFloat().coerceIn(0f, duration.toFloat()) else 0f,
            onValueChange = { value -> onSeek(value.toLong()) },
            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
            enabled = track != null && duration > 0,
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "${formatMusicDuration(positionMs)} / ${formatMusicDuration(durationMs)}",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        // 瀑布歌词（最终验收修改轮）：独立固定区域（320dp），v26
        // 放大到 360dp，位于进度条与播放控制之间。只受总开关与页内
        // 面板开关控制（showLyrics；关闭面板不影响悬浮歌词与播放），
        // 与歌曲列表的展开/收起无关——列表开关变化时歌词的位置、大
        // 小与显示状态不变。字号仍走 lyrics_font_size 键（默认
        // 22sp，范围 10–32），不硬编码；居中、平滑滚动与行级
        // 过渡动画在 LyricsView 内原样保留。
        if (showLyrics) {
            LyricsView(
                parsedLyrics = parsedLyrics,
                currentLyricIndex = currentLyricIndex,
                lyricsError = lyricsError,
                lyricsFontSize = lyricsFontSize,
                modifier = Modifier.fillMaxWidth().height(360.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        // 播放控制：上一首 / 播放暂停（放大）/ 下一首。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            IconButton(onClick = onPrevious, enabled = track != null) {
                Icon(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = "上一首"
                )
            }
            IconButton(
                onClick = onTogglePlayPause,
                enabled = track != null,
                modifier = Modifier.size(64.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    modifier = Modifier.size(40.dp)
                )
            }
            IconButton(onClick = onNext, enabled = track != null) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = "下一首"
                )
            }
        }
        // 播放模式（P1 逻辑原样接线：三档循环 + 随机开关）。
        // 顺序播放用 Repeat + 默认色；列表循环用 Repeat + 主色
        // （与顺序播放区分，靠主色 + 文字）；单曲循环用 RepeatOne
        // + 主色。随机开时 Shuffle 图标与“随机”文字同为主色。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            IconButton(onClick = onToggleRepeatMode) {
                Icon(
                    imageVector = if (repeatMode == MusicRepeatMode.SINGLE)
                        Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = PlaybackModeLogic.label(repeatMode),
                    tint = if (repeatMode == MusicRepeatMode.ORDER)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = PlaybackModeLogic.label(repeatMode),
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = if (repeatMode == MusicRepeatMode.ORDER)
                    MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            IconButton(onClick = onToggleShuffle) {
                Icon(
                    imageVector = Icons.Filled.Shuffle,
                    contentDescription = if (shuffleEnabled) "随机播放：开" else "随机播放：关",
                    tint = if (shuffleEnabled)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "随机",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = if (shuffleEnabled)
                    MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 音量（只影响本播放器音量）。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggleMute) {
                Icon(
                    imageVector = if (isMuted) Icons.Filled.VolumeOff
                        else Icons.Filled.VolumeUp,
                    contentDescription = if (isMuted) "取消静音" else "静音",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Slider(
                value = volume,
                onValueChange = onVolumeChange,
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f)
            )
        }
        // 歌词 / 悬浮开关与展开收起。
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onToggleLyrics) {
                Text(text = if (lyricsEnabled) "歌词：开" else "歌词：关")
            }
            // P0：内部悬浮歌词开关（总开关关闭时悬浮仍不显示；
            // 面板开关与悬浮开关各自独立）。
            TextButton(onClick = onToggleFloatingLyrics) {
                Text(text = if (floatingLyricsEnabled) "悬浮：开" else "悬浮：关")
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onToggleExpand) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = if (listExpanded) "收起列表" else "展开列表")
                    Icon(
                        imageVector = if (listExpanded) Icons.Filled.ExpandLess
                            else Icons.Filled.ExpandMore,
                        contentDescription = null
                    )
                }
            }
        }
    }
}

/**
 * 封面图或占位图（数据库 v7，播放器主区与播放列表共用）。
 *
 * - `coverPath != null` 时 Coil 异步加载该文件（File model，只读
 *   私有目录的缓存文件）；加载失败时 AsyncImage 不画内容，下层
 *   的占位图标露出——与无封面时同一张占位，不闪白、不画假封面。
 * - 切歌时调用方传入新曲目的 coverPath：model 变化即换图，旧图
 *   不残留；Coil 内存缓存命中时几乎无闪烁。
 * - App 内悬浮歌词面板与系统级悬浮歌词不使用本组件（当前设计
 *   没有封面，按任务书条件句处理，不改面板布局）。
 */
@Composable
private fun CoverImageOrPlaceholder(
    coverPath: String?,
    sizeDp: Dp,
    cornerDp: Dp,
    iconSizeDp: Dp,
    contentDescription: String?
) {
    Box(
        modifier = Modifier
            .size(sizeDp)
            .clip(RoundedCornerShape(cornerDp))
            .background(color = MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(cornerDp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(iconSizeDp)
        )
        if (coverPath != null) {
            AsyncImage(
                model = File(coverPath),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(sizeDp)
                    .clip(RoundedCornerShape(cornerDp))
            )
        }
    }
}

/** 播放列表的一行：封面缩略图 + 歌名 / 艺术家 · 时长 + 当前播放状态；
 * 点行直接播放；更多按钮进详情（追加 LRC / 删除）。 */
@Composable
private fun TrackRow(
    track: MusicTrack,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 封面缩略图（数据库 v7）：有封面加载真实图，否则占位图。
        CoverImageOrPlaceholder(
            coverPath = track.coverPath,
            sizeDp = 40.dp,
            cornerDp = 8.dp,
            iconSizeDp = 20.dp,
            contentDescription = track.title
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                fontSize = 14.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOfNotNull(
                    track.artist?.takeIf { it.isNotBlank() },
                    formatMusicDuration(track.durationMs)
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isCurrent && isPlaying) {
            Text(
                text = "播放中",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        IconButton(onClick = onMoreClick) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "更多操作"
            )
        }
    }
}

/**
 * 瀑布歌词视图（P2 阶段四，只改渲染层）。
 * - 有时间轴：当前行居中突出，前后行按与当前行的距离渐变弱化
 *   （alpha 渐变，见 LyricsWaterfallLogic）；行切换时平滑滚动
 *   （相邻行 animateScrollToItem，大幅跳变 scrollToItem 直接定位）
 *   + 行级过渡动画（alpha / 颜色 / 字号短时插值）。
 * - 无时间轴：整段展示。
 * - 无歌词：显示「当前歌曲暂无歌词」。
 * - 歌词读取失败：显示提示，不影响播放。
 * 暂停时歌词停留在当前进度（进度不推进，行不切换）；拖动进度后
 * 立即按新进度匹配；切换歌曲时先清空旧歌词再加载新歌词。
 * 颜色从 MaterialTheme.colorScheme 派生，不硬编码新颜色；
 * 字号取 `lyrics_font_size` 键（默认 22sp，范围 10–32，相邻行
 * 按 0.875 比例缩小），不跟随全局文字缩放。
 */
@Composable
private fun LyricsView(
    parsedLyrics: ParsedLyrics?,
    currentLyricIndex: Int,
    lyricsError: String?,
    modifier: Modifier = Modifier,
    lyricsFontSize: Float = AppearanceLogic.DEFAULT_LYRICS_FONT_SIZE
) {
    // P2 stage 5: page lyrics ignore the global text scale (their size
    // comes only from `lyrics_font_size`); colour still follows the
    // global text colour through colorScheme, as before.
    val adjacentFontSize = lyricsFontSize *
        AppearanceLogic.LYRICS_ADJACENT_SIZE_RATIO
    if (lyricsError != null) {
        Text(
            text = lyricsError,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp,
            modifier = modifier.padding(vertical = 8.dp)
        )
        return
    }
    if (parsedLyrics == null) {
        Text(
            text = "当前歌曲暂无歌词",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(vertical = 8.dp)
        )
        return
    }
    // Rendering only from here on runs unscaled: the early returns
    // above must stay outside UnscaledDensity (a bare return is not
    // allowed inside its non-inline lambda).
    UnscaledDensity {
    if (!parsedLyrics.hasTimestamps) {
        val scrollState = rememberScrollState()
        Column(
            modifier = modifier.verticalScroll(scrollState).padding(vertical = 8.dp)
        ) {
            parsedLyrics.lines.forEach { line ->
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = adjacentFontSize.sp,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    } else {
    val timed = parsedLyrics.timedLines
    val listState = rememberLazyListState()
    var lastLyricIndex by remember { mutableStateOf(-1) }
    // 切歌：歌词列表换成新曲目时立即定位到新曲目的当前行，
    // 不做跨歌平滑滚动，不残留上一首歌词。
    LaunchedEffect(parsedLyrics) {
        lastLyricIndex = -1
        val target = currentLyricIndex.coerceIn(0, (timed.size - 1).coerceAtLeast(0))
        listState.scrollToItem(target)
    }
    // 行切换：相邻行推进用平滑滚动，大幅跳变（拖进度、切歌后
    // 首行定位）用直接定位；进度在第一行之前时回到顶部。
    LaunchedEffect(currentLyricIndex) {
        if (currentLyricIndex < 0) {
            lastLyricIndex = -1
            listState.scrollToItem(0)
            return@LaunchedEffect
        }
        val target = currentLyricIndex.coerceIn(0, (timed.size - 1).coerceAtLeast(0))
        if (LyricsWaterfallLogic.isLargeLyricJump(lastLyricIndex, currentLyricIndex)) {
            listState.scrollToItem(target)
        } else {
            listState.animateScrollToItem(target)
        }
        lastLyricIndex = currentLyricIndex
    }
    BoxWithConstraints(modifier = modifier) {
        // 当前行居中：上下内边距各约半个容器高，使滚动目标行落在
        // 容器中部。优先用 contentPadding，不依赖实测行高，长句
        // 换行后居中也不漂移。
        val centerPadding = (maxHeight / 2 - (lyricsFontSize / 2).dp).coerceAtLeast(0.dp)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = centerPadding),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(count = timed.size, key = { it }) { index ->
                val line = timed[index]
                // 行级过渡动画：只在 currentLyricIndex 变化时触发；
                // alpha 按与当前行的距离渐变，颜色与字号短时插值，
                // 不由播放进度连续驱动，不高频重组整页。
                val targetAlpha =
                    LyricsWaterfallLogic.rowAlpha(abs(index - currentLyricIndex))
                val alpha by animateFloatAsState(
                    targetValue = targetAlpha,
                    animationSpec = tween(durationMillis = 300),
                    label = "lyricAlpha"
                )
                val targetFontSize = if (index == currentLyricIndex) lyricsFontSize else adjacentFontSize
                val fontSize by animateFloatAsState(
                    targetValue = targetFontSize,
                    animationSpec = tween(durationMillis = 200),
                    label = "lyricSize"
                )
                val targetColor = if (index == currentLyricIndex)
                    MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
                val color by animateColorAsState(
                    targetValue = targetColor,
                    animationSpec = tween(durationMillis = 300),
                    label = "lyricColor"
                )
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = fontSize.sp,
                    fontWeight = if (index == currentLyricIndex) FontWeight.Bold
                        else FontWeight.Normal,
                    color = color.copy(alpha = alpha),
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
    }
}
}
