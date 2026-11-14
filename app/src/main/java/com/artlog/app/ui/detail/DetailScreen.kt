package com.artlog.app.ui.detail

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.artlog.app.data.ArtworkDates
import com.artlog.app.data.ArtworkStatus
import com.artlog.app.data.ArtworkWithImages
import com.artlog.app.ui.ArtlogViewModelFactory
import com.artlog.app.ui.tags.TagPickerDialog
import java.io.File

/**
 * Artwork detail as a private digital gallery (P2 stage 3).
 *
 * - The artwork is the protagonist: the main image takes the available
 *   screen space at its original aspect ratio (ContentScale.Fit, never
 *   cropped), and it is zoomable and draggable in place — the
 *   full-screen-only [ZoomableImage] is now the main-image interaction.
 * - Immersive viewing hides the top bar and all chrome (and the
 *   system bars); a single tap on the image exits immersive mode.
 *   The in-app floating lyrics overlay stays on top while immersive
 *   — that is the pre-existing P0 behaviour and is not hidden here;
 *   only the user turning the lyrics switch off in the Music page
 *   hides it.
 * - Information is split into collapsible sections (basic info, tags,
 *   creation summary, linked journal entries). Fold state is
 *   in-memory only and resets when another artwork is opened.
 * - Prev/next walks the ordered id list the user came in from (the
 *   Gallery list after its current filters and sort). Every piece of
 *   displayed data follows the current artwork through the ViewModel,
 *   so switching artworks never leaves stale data behind.
 * - Delete still requires confirmation; a failed delete now shows an
 *   error instead of failing silently.
 *
 * Original image files are never modified or recompressed for the UI;
 * display keeps going through the existing Coil configuration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    onBackClick: () -> Unit,
    onEditClick: (String) -> Unit,
    onDeleted: () -> Unit,
    onJournalEntryClick: (String) -> Unit,
    viewModelFactory: ArtlogViewModelFactory,
    artworkId: String,
    /** Ordered ids of the list the user came in from; empty = no prev/next. */
    artworkIds: List<String> = emptyList()
) {
    // One stable ViewModel instance for the whole detail session:
    // keyed by a constant so returning from the edit page (which
    // changes artworkId to the saved id) does not drop the list or
    // the current artwork. Sync happens in the LaunchedEffect below.
    val viewModel: DetailViewModel = viewModel(
        key = "detail",
        factory = viewModelFactory.detailFactory(artworkId, artworkIds)
    )
    LaunchedEffect(artworkId, artworkIds) {
        viewModel.openArtwork(artworkId, artworkIds)
    }

    val artworkWithImages by viewModel.artwork.collectAsState()
    val currentArtworkId by viewModel.currentArtworkId.collectAsState()
    val hasPrevious by viewModel.hasPrevious.collectAsState()
    val hasNext by viewModel.hasNext.collectAsState()
    val positionLabel by viewModel.positionLabel.collectAsState()
    val showDeleteConfirm by viewModel.showDeleteConfirm.collectAsState()
    val deleted by viewModel.deleted.collectAsState()
    val deleteError by viewModel.deleteError.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val allTags by viewModel.allTags.collectAsState()
    val linkedEntries by viewModel.linkedEntries.collectAsState()

    var selectedImageIndex by remember { mutableIntStateOf(0) }
    var showFullscreen by remember { mutableStateOf(false) }
    var showTagPicker by remember { mutableStateOf(false) }
    var immersive by remember { mutableStateOf(false) }

    LaunchedEffect(deleted) {
        if (deleted) onDeleted()
    }

    // Immersive mode: hide the system bars while active, and restore
    // them when leaving immersive mode or leaving the page. System
    // back exits immersive first (the App shell's handler only runs
    // while this one is disabled).
    val context = LocalContext.current
    DisposableEffect(immersive) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (immersive) {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    BackHandler(enabled = immersive) { immersive = false }

    val item = artworkWithImages
    // The selected image index is clamped to the current artwork's
    // image count, so an index left over from a previous artwork
    // (e.g. image 3 of a 4-image work) can never point past the end
    // of a work with fewer images.
    val selectedIndex = if (item == null) 0 else
        selectedImageIndex.coerceIn(0, (item.images.size - 1).coerceAtLeast(0))
    Column(modifier = Modifier.fillMaxSize()) {
        if (!immersive) {
            TopAppBar(
                title = { Text(text = item?.artwork?.title ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { onEditClick(currentArtworkId) },
                        enabled = item != null
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "编辑"
                        )
                    }
                    IconButton(
                        onClick = { viewModel.requestDelete() },
                        enabled = item != null
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "删除"
                        )
                    }
                },
                // 2026-11-14 v27 作品查看页顶栏修复：与设置页 v25、视觉灵感
                // v26 同款问题。
                // (1) 容器色改为透明，背景图透出（原来是不透明的
                // colorScheme.background #111214，挡住了背景图）。
                // (2) 只保留水平方向 insets：ArtlogApp 的 Scaffold 内容区
                // 已经给了状态栏高度的 padding，TopAppBar 默认还会再加
                // 一份状态栏 inset（M3 1.3.0 实测），两份叠加使标题行
                // 下沉约一个状态栏高度、黑带增高；去掉重复的那份即可。
                // 沉浸模式下本 TopAppBar 不参与组合（if (!immersive)），
                // 沉浸进出逻辑一行未动。
                windowInsets = TopAppBarDefaults.windowInsets.only(
                    WindowInsetsSides.Horizontal
                ),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }

        if (item == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "找不到这幅作品，它可能已被删除。",
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = 14.sp
                )
            }
        } else if (immersive) {
            // Immersive viewing: the artwork alone, zoomable and
            // draggable. Tap the image (or the close button) to exit.
            val immersiveImage = item.images.getOrNull(selectedIndex)
                ?: item.images.firstOrNull()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                if (immersiveImage != null) {
                    key(immersiveImage.imagePath) {
                        ZoomableImage(
                            model = File(immersiveImage.imagePath),
                            contentDescription = item.artwork.title,
                            modifier = Modifier.fillMaxSize(),
                            onTap = { immersive = false }
                        )
                    }
                }
                IconButton(
                    onClick = { immersive = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "退出沉浸式"
                    )
                }
            }
        } else {
            val artwork = item.artwork
            val images = item.images
            // Reset the selected image when switching artworks so a
            // stale thumbnail index from the previous artwork cannot
            // point at the wrong image.
            val selected = images.getOrNull(selectedIndex)
                ?: images.firstOrNull()
            key(artwork.id) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                ) {
                    // Main image: original aspect ratio, zoomable and
                    // draggable in place; tap enters immersive mode.
                    if (selected != null) {
                        var imageLoading by remember(selected.imagePath) {
                            mutableStateOf(true)
                        }
                        var imageFailed by remember(selected.imagePath) {
                            mutableStateOf(false)
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                        ) {
                            key(selected.imagePath) {
                                ZoomableImage(
                                    model = File(selected.imagePath),
                                    contentDescription = artwork.title,
                                    modifier = Modifier.fillMaxWidth(),
                                    onTap = { immersive = true },
                                    onLoadingChange = { loading ->
                                        imageLoading = loading
                                        if (loading) imageFailed = false
                                    },
                                    onErrorChange = { failed ->
                                        imageFailed = failed
                                        if (failed) imageLoading = false
                                    }
                                )
                            }
                            if (imageLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(32.dp)
                                )
                            }
                            if (imageFailed) {
                                Text(
                                    text = "图片加载失败",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontSize = 14.sp,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                            IconButton(
                                onClick = { showFullscreen = true },
                                modifier = Modifier.align(Alignment.TopEnd)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Fullscreen,
                                    contentDescription = "全屏查看"
                                )
                            }
                        }
                    }

                    // Thumbnail strip for multi-image artworks.
                    if (images.size > 1) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            images.forEachIndexed { index, image ->
                                AsyncImage(
                                    model = File(image.thumbnailPath),
                                    contentDescription = "图片 ${index + 1}",
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { selectedImageIndex = index }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Prev / next inside the list the user came in
                    // from, plus an explicit way back to the Gallery.
                    if (positionLabel != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    viewModel.goToPrevious()
                                    selectedImageIndex = 0
                                },
                                enabled = hasPrevious
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                    contentDescription = null
                                )
                                Text(text = "上一张")
                            }
                            Text(
                                text = positionLabel!!,
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(
                                onClick = {
                                    viewModel.goToNext()
                                    selectedImageIndex = 0
                                },
                                enabled = hasNext
                            ) {
                                Text(text = "下一张")
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null
                                )
                            }
                        }
                    }
                    TextButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                        Text(text = "返回作品库")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Basic info (collapsible).
                    var infoExpanded by remember(artwork.id) { mutableStateOf(true) }
                    CollapsibleSection(
                        title = "基本信息",
                        expanded = infoExpanded,
                        onToggle = { infoExpanded = !infoExpanded }
                    ) {
                        InfoRow(label = "作品名称", value = artwork.title)
                        InfoRow(label = "创作日期", value = ArtworkDates.formatDay(artwork.createdAt))
                        InfoRow(label = "添加日期", value = ArtworkDates.formatDay(artwork.addedAt))
                        InfoRow(label = "最后修改", value = ArtworkDates.formatDay(artwork.updatedAt))
                        InfoRow(
                            label = "创作耗时",
                            value = artwork.durationMinutes?.let { "$it 分钟" } ?: "未填写"
                        )
                        InfoRow(
                            label = "作品状态",
                            value = ArtworkStatus.fromName(artwork.status).label
                        )
                        InfoRow(label = "图片数量", value = "${images.size} 张")
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    // Tags (round 3, collapsible).
                    var tagsExpanded by remember(artwork.id) { mutableStateOf(true) }
                    CollapsibleSection(
                        title = "标签",
                        expanded = tagsExpanded,
                        onToggle = { tagsExpanded = !tagsExpanded }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { showTagPicker = true }) {
                                Text(text = "编辑标签")
                            }
                        }
                        if (tags.isEmpty()) {
                            Text(
                                text = "还没有标签，点「编辑标签」可以加上。",
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 14.sp
                            )
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                tags.forEach { tag ->
                                    androidx.compose.material3.AssistChip(
                                        onClick = { showTagPicker = true },
                                        label = { Text(text = tag.name) }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    // Creation summary (collapsible; long texts fold).
                    val summary = listOf(
                        "创作初衷" to artwork.intention,
                        "创作笔记" to artwork.description,
                        "自己满意的地方" to artwork.reflection,
                        "下次改进的方向" to artwork.improvement
                    ).filter { it.second.isNotBlank() }
                    var summaryExpanded by remember(artwork.id) { mutableStateOf(true) }
                    CollapsibleSection(
                        title = "创作总结",
                        expanded = summaryExpanded,
                        onToggle = { summaryExpanded = !summaryExpanded }
                    ) {
                        if (summary.isEmpty()) {
                            Text(
                                text = "还没有写创作总结，点右上角的编辑可以补上。",
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 14.sp
                            )
                        } else {
                            summary.forEach { (label, value) ->
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                ExpandableText(text = value)
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    // Linked journal entries (round 3, collapsible).
                    var journalExpanded by remember(artwork.id) { mutableStateOf(true) }
                    CollapsibleSection(
                        title = "关联手记",
                        expanded = journalExpanded,
                        onToggle = { journalExpanded = !journalExpanded }
                    ) {
                        if (linkedEntries.isEmpty()) {
                            Text(
                                text = "还没有关联这幅作品的手记，可以在手记页写一篇并关联它。",
                                style = MaterialTheme.typography.bodyMedium,
                                fontSize = 14.sp
                            )
                        } else {
                            linkedEntries.forEach { entry ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onJournalEntryClick(entry.id) }
                                        .padding(vertical = 6.dp)
                                ) {
                                    Text(
                                        text = entry.title.ifBlank { "手记" },
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = ArtworkDates.formatDay(entry.createdAt),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    // Full-screen zoomable view (kept from the pre-P2 page).
    val fullscreenImage = item?.images?.getOrNull(selectedIndex)
    if (showFullscreen && fullscreenImage != null) {
        Dialog(
            onDismissRequest = { showFullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                var fullscreenLoading by remember(fullscreenImage.imagePath) {
                    mutableStateOf(true)
                }
                key(fullscreenImage.imagePath) {
                    ZoomableImage(
                        model = File(fullscreenImage.imagePath),
                        contentDescription = item?.artwork?.title ?: "",
                        modifier = Modifier.fillMaxSize(),
                        onLoadingChange = { fullscreenLoading = it }
                    )
                }
                if (fullscreenLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(32.dp)
                    )
                }
                IconButton(
                    onClick = { showFullscreen = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭"
                    )
                }
            }
        }
    }

    // Delete confirmation.
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDelete() },
            title = { Text(text = "删除作品") },
            text = {
                Text(
                    text = "确定要删除「${item?.artwork?.title ?: ""}」吗？" +
                        "数据库记录和图片文件都会一起删除，这个操作无法撤销。"
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDelete() }) {
                    Text(text = "删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDelete() }) {
                    Text(text = "取消")
                }
            }
        )
    }

    // Delete failure (P2: previously silent — confirmDelete had no
    // try/catch and a failed delete simply did nothing).
    if (deleteError != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteError() },
            title = { Text(text = "删除失败") },
            text = { Text(text = deleteError!!) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissDeleteError() }) {
                    Text(text = "知道了")
                }
            }
        )
    }

    // Tag picker (round 3).
    if (showTagPicker) {
        TagPickerDialog(
            allTags = allTags,
            selectedIds = tags.map { it.id }.toSet(),
            onSelectionChange = { viewModel.setTags(it) },
            onCreateTag = { viewModel.createTag(it) },
            onDismiss = { showTagPicker = false }
        )
    }
}

/**
 * A titled info section that can be folded. Fold state is held by
 * the caller (in-memory only, reset per artwork).
 */
@Composable
private fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onToggle) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开"
            )
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    if (expanded) {
        content()
    }
}

/**
 * A long text that folds to three lines with an expand control.
 * Short texts (see [DetailTextLogic]) are shown in full with no
 * fold control at all.
 */
@Composable
private fun ExpandableText(text: String) {
    if (!DetailTextLogic.needsCollapse(text)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            fontSize = 14.sp
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        fontSize = 14.sp,
        maxLines = if (expanded) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(modifier = Modifier.height(4.dp))
    TextButton(onClick = { expanded = !expanded }) {
        Text(text = if (expanded) "收起" else "展开全文")
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp,
            modifier = Modifier.width(96.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Real pinch-zoom + drag image viewer.
 *
 * - Pinch to zoom (1x–5x), drag to pan while zoomed.
 * - Double-tap toggles between 1x and 2.5x.
 * - Zooming back to 1x resets the pan offset.
 * - Single tap calls [onTap] (the gallery page uses it to enter /
 *   exit immersive mode); null keeps the old tap behaviour.
 * - [onLoadingChange] / [onErrorChange] report the Coil load state
 *   so callers can show a loading indicator or an error message.
 *   The composable itself is keyed by image path at the call site so
 *   zoom / pan reset when the image changes.
 */
@Composable
fun ZoomableImage(
    model: Any,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    onLoadingChange: ((Boolean) -> Unit)? = null,
    onErrorChange: ((Boolean) -> Unit)? = null
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        onLoading = {
            onLoadingChange?.invoke(true)
            onErrorChange?.invoke(false)
        },
        onSuccess = { onLoadingChange?.invoke(false) },
        onError = {
            onLoadingChange?.invoke(false)
            onErrorChange?.invoke(true)
        },
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                    scale = newScale
                    offset = if (newScale <= 1f) Offset.Zero else offset + pan
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap?.invoke() },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    }
                )
            }
    )
}
