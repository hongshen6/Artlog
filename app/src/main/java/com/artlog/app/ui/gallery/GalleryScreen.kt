package com.artlog.app.ui.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import com.artlog.app.data.ArtworkDates
import com.artlog.app.data.ArtworkStatus
import com.artlog.app.data.ArtworkWithImages
import com.artlog.app.ui.ArtlogViewModelFactory
import java.io.File

/**
 * Gallery (spec V1.0, section 3.2): real two-column grid from the
 * database, newest first by default. Images use ContentScale.FillWidth —
 * the full picture at its original aspect ratio, never cropped.
 * Search, status filter, sort toggle and batch select + delete (with
 * confirmation) are all real. Round 3 adds the tag filter row and the
 * entry point to tag management.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    /**
     * P2 gallery: opening a work also hands over the ordered ids of
     * the list currently on screen (after search / status / tag
     * filters and sort), so the detail page's prev/next walks exactly
     * the list the user sees. The filter/sort logic itself is
     * untouched — only the already-computed order is passed along.
     */
    onArtworkClick: (String, List<String>) -> Unit,
    onManageTagsClick: () -> Unit,
    viewModelFactory: ArtlogViewModelFactory
) {
    val viewModel: GalleryViewModel = viewModel(factory = viewModelFactory)
    val state by viewModel.uiState.collectAsState()
    var sortMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        // Title row: in selection mode it becomes the selection bar.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.selectionMode) {
                IconButton(onClick = { viewModel.exitSelectionMode() }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "取消选择",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "已选 ${state.selectedIds.size} 幅",
                    style = MaterialTheme.typography.titleLarge,
                    fontSize = 20.sp
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { viewModel.requestDelete(state.selectedIds) }) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "删除所选",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                Text(
                    text = "作品库",
                    style = MaterialTheme.typography.titleLarge,
                    fontSize = 22.sp
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "${state.totalCount} 幅",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp
                )
            }
        }

        // Search.
        OutlinedTextField(
            value = state.query,
            onValueChange = { viewModel.setQuery(it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(text = "按标题搜索") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Status filter chips + sort menu.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.statusFilter == null,
                    onClick = { viewModel.setStatusFilter(null) },
                    label = { Text(text = "全部") }
                )
                ArtworkStatus.entries.forEach { status ->
                    FilterChip(
                        selected = state.statusFilter == status,
                        onClick = { viewModel.setStatusFilter(status) },
                        label = { Text(text = status.label) }
                    )
                }
            }
            Box {
                TextButton(onClick = { sortMenuExpanded = true }) {
                    Text(text = "排序：${state.sort.label}")
                }
                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false }
                ) {
                    GallerySort.entries.forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(text = sort.label) },
                            onClick = {
                                viewModel.setSort(sort)
                                sortMenuExpanded = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Tag filter chips + tag management entry (round 3).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = state.tagFilter == null,
                onClick = { viewModel.setTagFilter(null) },
                label = { Text(text = "全部标签") }
            )
            state.allTags.forEach { tag ->
                FilterChip(
                    selected = state.tagFilter?.id == tag.id,
                    onClick = { viewModel.setTagFilter(tag) },
                    label = { Text(text = tag.name) }
                )
            }
            TextButton(onClick = onManageTagsClick) {
                Text(text = "管理标签")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Grid.
        if (state.artworks.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = if (state.totalCount == 0) "还没有作品" else "没有符合条件的作品",
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (state.totalCount == 0) "点下方中间的 + ，添加你的第一幅作品。"
                    else "换个关键词或筛选条件试试。",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(state.artworks, key = { it.artwork.id }) { item ->
                    GalleryCard(
                        item = item,
                        selected = state.selectedIds.contains(item.artwork.id),
                        selectionMode = state.selectionMode,
                        onClick = {
                            if (state.selectionMode) {
                                viewModel.toggleSelection(item.artwork.id)
                            } else {
                                onArtworkClick(
                                    item.artwork.id,
                                    state.artworks.map { it.artwork.id }
                                )
                            }
                        },
                        onLongClick = { viewModel.enterSelectionMode(item.artwork.id) }
                    )
                }
            }
        }
    }

    // Delete confirmation (single artwork or batch).
    val pendingDelete = state.confirmDeleteIds
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteConfirm() },
            title = { Text(text = "删除作品") },
            text = {
                Text(
                    text = "确定要删除选中的 ${pendingDelete.size} 幅作品吗？" +
                        "数据库记录和图片文件都会一起删除，这个操作无法撤销。"
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDelete() }) {
                    Text(text = "删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeleteConfirm() }) {
                    Text(text = "取消")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryCard(
    item: ArtworkWithImages,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(
                if (selected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(12.dp)
                    )
                } else {
                    Modifier
                }
            )
            .padding(2.dp)
    ) {
        Box {
            val cover = item.artwork.coverPath
            if (cover != null) {
                AsyncImage(
                    model = File(cover),
                    contentDescription = item.artwork.title,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                )
            }
            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .padding(8.dp)
                        .size(24.dp)
                        .background(
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "已选",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = item.artwork.title,
            style = MaterialTheme.typography.bodyLarge,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${ArtworkDates.formatDay(item.artwork.createdAt)} · " +
                ArtworkStatus.fromName(item.artwork.status).label,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp
        )
    }
}
