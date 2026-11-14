package com.artlog.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.artlog.app.data.AppearanceSettings
import com.artlog.app.data.ArtworkDates
import com.artlog.app.data.ArtworkWithImages
import com.artlog.app.data.HomeTitleLogic
import com.artlog.app.playback.MusicPlaybackState
import com.artlog.app.ui.ArtlogViewModelFactory
import java.io.File

/**
 * Home screen (P2: private creative studio).
 *
 * Layout rules (section 2.4): 20dp side margins, 12-16dp content
 * spacing, 24-32dp between sections, 10-14dp corner radii.
 *
 * P2 redesign (task §三): the home page is a studio, not a second
 * Gallery. Sections, in order: brand bar / manifesto (editable
 * title + last-creation line) / three-number overview (reused
 * HomeViewModel statistics, real rows only) / recent-creation
 * timeline (recentlyUpdated, first 3) / in-progress (only items
 * backed by real data: now playing, most recently edited artwork,
 * latest journal entry) / studio entries in two columns
 * (Gallery / Journal / Visual Inspiration / Music). The old
 * two-column large-image grid and the "recent activity" text list
 * are gone; both are replaced by the timeline.
 *
 * Data honesty rules for this page:
 * - No invented state: an item is shown only when its data exists.
 * - No fake cover art: MusicTrack has no cover field, so the
 *   now-playing card uses a placeholder icon, never a picture.
 * - Timeline thumbnails use the generated thumbnailPath only; the
 *   original image file is never loaded here.
 */
@Composable
fun HomeScreen(
    onSettingsClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onJournalClick: () -> Unit,
    onArtworkClick: (String) -> Unit,
    onInspirationClick: () -> Unit,
    onMusicClick: () -> Unit,
    appearanceSettings: AppearanceSettings,
    musicPlaybackState: MusicPlaybackState,
    viewModelFactory: ArtlogViewModelFactory
) {
    val viewModel: HomeViewModel = viewModel(factory = viewModelFactory)
    val state by viewModel.uiState.collectAsState()
    val appearance by appearanceSettings.state.collectAsState()
    val currentTrack by musicPlaybackState.currentTrack.collectAsState()
    val isPlaying by musicPlaybackState.isPlaying.collectAsState()
    val scrollState = rememberScrollState()
    var showTitleEditor by remember { mutableStateOf(false) }

    if (showTitleEditor) {
        EditHomeTitleDialog(
            currentTitle = appearance.homeTitle,
            onDismiss = { showTitleEditor = false },
            onSave = { title ->
                appearanceSettings.setHomeTitle(title)
                showTitleEditor = false
            },
            onRestoreDefault = {
                appearanceSettings.clearHomeTitle()
                showTitleEditor = false
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Top
    ) {
        // Top brand bar: "Artlog." left, search + settings right.
        // Unchanged from the pre-P2 home page.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Artlog.",
                style = MaterialTheme.typography.titleLarge,
                fontSize = 22.sp
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onGalleryClick) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = "搜索",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Manifesto: editable title + subtitle + last-creation line.
        // The title reads the home_title setting (default: the old
        // hard-coded headline); the pencil opens the edit dialog.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = appearance.homeTitle,
                style = MaterialTheme.typography.headlineLarge,
                fontSize = 26.sp,
                modifier = Modifier.weight(1f, fill = false)
            )
            IconButton(onClick = { showTitleEditor = true }) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = "编辑标题",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "PERSONAL ART ARCHIVE",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            letterSpacing = 2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        // Last creation: recentWorks[0] (addedAt desc) — its createdAt
        // (creation date) plus its own durationMinutes. A missing
        // duration is shown as "未填写", never invented; with no works
        // the line shows the empty state.
        val lastWork = state.recentWorks.firstOrNull()
        Text(
            text = if (lastWork == null) {
                "最后创作：暂无作品"
            } else {
                "最后创作：" + ArtworkDates.formatDay(lastWork.artwork.createdAt) +
                    " · " + (lastWork.artwork.durationMinutes?.let { "$it 分钟" } ?: "未填写")
            },
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "最近一次创作的日期与耗时。",
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Data overview: real values from the database (reused).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatItem(
                value = state.artworkCount.toString(),
                unit = null,
                label = "作品总数"
            )
            StatItem(
                value = state.totalDurationMinutes.toString(),
                unit = "分钟",
                label = "累计创作时长"
            )
            StatItem(
                value = state.thisMonthCount.toString(),
                unit = null,
                label = "本月新增作品数"
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Recent creations: timeline, latest 3 by updatedAt.
        // Not a Gallery grid: thumbnail + title + update date +
        // duration (when filled) + creation-note summary (when filled).
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "最近创作",
                style = MaterialTheme.typography.titleMedium,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "查看全部",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = onGalleryClick)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (state.recentActivity.isEmpty()) {
            EmptyStateBox(
                title = "还没有作品",
                hint = "点下方中间的 + ，添加你的第一幅作品。"
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                state.recentActivity.take(3).forEachIndexed { index, item ->
                    TimelineRow(
                        item = item,
                        isLast = index == state.recentActivity.take(3).lastIndex,
                        onClick = { onArtworkClick(item.artwork.id) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // In progress: only items backed by real data — the track
        // currently in the player (if any), the most recently edited
        // artwork (if any), the latest journal entry (if any).
        // "Unfinished" is deliberately NOT shown: there is no agreed
        // source for it (see the P2 home report), and this page never
        // displays an invented creation state.
        Text(
            text = "正在进行",
            style = MaterialTheme.typography.titleMedium,
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        val latestEdited = state.recentActivity.firstOrNull()
        val latestJournal = state.latestJournal
        if (currentTrack == null && latestEdited == null && latestJournal == null) {
            EmptyStateBox(
                title = "还没有进行中的内容",
                hint = "播放一首音乐、编辑一幅作品或写一篇手记后，这里会出现它。"
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (currentTrack != null) {
                    NowPlayingCard(
                        title = currentTrack!!.title,
                        artist = currentTrack!!.artist,
                        coverPath = currentTrack!!.coverPath,
                        isPlaying = isPlaying,
                        onTogglePlayPause = { musicPlaybackState.togglePlayPause() },
                        onOpenMusic = onMusicClick
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable(onClick = onMusicClick)
                            .padding(vertical = 14.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "还没有播放音乐，去音乐页选一首",
                            style = MaterialTheme.typography.bodyLarge,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (latestEdited != null) {
                    InProgressRow(
                        label = "最近编辑",
                        title = latestEdited.artwork.title,
                        meta = "更新于 " +
                            ArtworkDates.formatMonthDay(latestEdited.artwork.updatedAt),
                        onClick = { onArtworkClick(latestEdited.artwork.id) }
                    )
                }
                if (latestJournal != null) {
                    InProgressRow(
                        label = "最近手记",
                        title = latestJournal.title,
                        meta = ArtworkDates.formatMonthDay(latestJournal.createdAt),
                        onClick = onJournalClick
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Studio entries: compact two-column grid. Statistics stays
        // in the bottom navigation and is not repeated here.
        Text(
            text = "工作室",
            style = MaterialTheme.typography.titleMedium,
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(
                StudioEntry(
                    name = "作品库",
                    description = "浏览与管理全部作品",
                    icon = { tint ->
                        Icon(
                            imageVector = Icons.Filled.Collections,
                            contentDescription = null,
                            tint = tint
                        )
                    },
                    onClick = onGalleryClick
                ),
                StudioEntry(
                    name = "手记",
                    description = "记录创作手记",
                    icon = { tint ->
                        Icon(
                            imageVector = Icons.Filled.Book,
                            contentDescription = null,
                            tint = tint
                        )
                    },
                    onClick = onJournalClick
                ),
                StudioEntry(
                    name = "视觉灵感",
                    description = "发现值得探索的视觉创作网站",
                    icon = { tint ->
                        Icon(
                            imageVector = Icons.Filled.Explore,
                            contentDescription = null,
                            tint = tint
                        )
                    },
                    onClick = onInspirationClick
                ),
                StudioEntry(
                    name = "音乐",
                    description = "本地音乐与歌词",
                    icon = { tint ->
                        Icon(
                            imageVector = Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = tint
                        )
                    },
                    onClick = onMusicClick
                )
            ).chunked(2).forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    row.forEach { entry ->
                        StudioEntryCard(
                            entry = entry,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (row.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** One timeline row: dot + rail, 56dp thumbnail, title, meta, summary. */
@Composable
private fun TimelineRow(
    item: ArtworkWithImages,
    isLast: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable(onClick = onClick)
    ) {
        // Timeline rail: dot on top, line down to the next row.
        Column(
            modifier = Modifier
                .width(16.dp)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = CircleShape
                    )
            )
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .weight(1f)
                        .padding(top = 4.dp)
                        .background(color = MaterialTheme.colorScheme.outline)
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Thumbnail only: the generated thumbnailPath. When an
                // artwork has no thumbnail, show a placeholder box —
                // the original image file is never loaded here.
                val thumbnailPath = item.images.firstOrNull()?.thumbnailPath
                if (thumbnailPath != null) {
                    AsyncImage(
                        model = File(thumbnailPath),
                        contentDescription = item.artwork.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(10.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.artwork.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "更新于 " +
                            ArtworkDates.formatMonthDay(item.artwork.updatedAt) +
                            (item.artwork.durationMinutes?.let { " · 耗时 $it 分钟" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Creation-note summary, only when one was written.
            if (item.artwork.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = item.artwork.description,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Now-playing card. Cover art (database v7): when the current track
 * has a coverPath, Coil loads the cached cover file; otherwise (or
 * on load failure) the placeholder icon shows. Play/pause drives
 * the existing app-scoped [MusicPlaybackState]; no second player
 * is created and background playback is untouched.
 */
@Composable
private fun NowPlayingCard(
    title: String,
    artist: String?,
    coverPath: String?,
    isPlaying: Boolean,
    onTogglePlayPause: () -> Unit,
    onOpenMusic: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onOpenMusic)
            .padding(vertical = 12.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 封面（数据库 v7）：占位图标垫底，有封面时 AsyncImage
        // 盖在上层；加载失败时不画内容，占位自然露出。
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(color = MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (coverPath != null) {
                AsyncImage(
                    model = File(coverPath),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = (artist?.takeIf { it.isNotBlank() } ?: "未知艺术家") +
                    " · " + if (isPlaying) "播放中" else "已暂停",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onTogglePlayPause) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** One "in progress" row: small label + title + meta, opens its target. */
@Composable
private fun InProgressRow(
    label: String,
    title: String,
    meta: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private data class StudioEntry(
    val name: String,
    val description: String,
    val icon: @Composable (tint: androidx.compose.ui.graphics.Color) -> Unit,
    val onClick: () -> Unit
)

@Composable
private fun StudioEntryCard(entry: StudioEntry, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = entry.onClick)
            .padding(vertical = 16.dp, horizontal = 16.dp)
    ) {
        entry.icon(MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyLarge,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = entry.description,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Edit-home-title dialog (P2). Input longer than
 * [HomeTitleLogic.MAX_TITLE_LENGTH] is rejected at typing time;
 * the counter shows the live length; Save is disabled while the
 * text is blank. "恢复默认" deletes the key and the title falls
 * back to the default immediately.
 */
@Composable
private fun EditHomeTitleDialog(
    currentTitle: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onRestoreDefault: () -> Unit
) {
    var text by remember { mutableStateOf(currentTitle) }
    val saveable = HomeTitleLogic.isSaveable(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "编辑标题",
                style = MaterialTheme.typography.titleMedium,
                fontSize = 16.sp
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { new ->
                        if (new.length <= HomeTitleLogic.MAX_TITLE_LENGTH) text = new
                    },
                    singleLine = true,
                    placeholder = { Text(text = "输入首页标题") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    if (text.isBlank()) {
                        Text(
                            text = "标题不能为空",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${text.length} / ${HomeTitleLogic.MAX_TITLE_LENGTH}",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onRestoreDefault) {
                    Text(text = "恢复默认")
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(text = "取消")
                }
                TextButton(
                    onClick = { onSave(text) },
                    enabled = saveable
                ) {
                    Text(text = "保存")
                }
            }
        }
    )
}

@Composable
private fun StatItem(value: String, unit: String?, label: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineLarge,
                fontSize = 28.sp
            )
            if (unit != null) {
                Text(
                    text = " $unit",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun EmptyStateBox(title: String, hint: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(vertical = 24.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}
