package com.artlog.app.ui.add

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.artlog.app.data.ArtworkDates
import com.artlog.app.data.ArtworkStatus
import com.artlog.app.ui.ArtlogViewModelFactory
import com.artlog.app.ui.tags.TagPickerDialog
import java.io.File

/**
 * Add / edit artwork (spec V1.0, section 3.3).
 *
 * Create: Android Photo Picker (multi-select), tap a thumbnail to choose
 * the cover, then title, creation date, duration, status and notes.
 * Saving shows a loading state; on failure the filled-in form is kept and
 * the error is shown. The artwork ID is generated on save.
 * Edit: the same form, prefilled; images are shown read-only and the
 * cover can be switched between the existing images.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddScreen(
    onSaved: (String) -> Unit,
    viewModelFactory: ArtlogViewModelFactory,
    editArtworkId: String? = null,
    /** Back entry, only used in edit mode (an overlay). Null in create mode. */
    onBackClick: (() -> Unit)? = null
) {
    val viewModel: AddViewModel = if (editArtworkId != null) {
        viewModel(
            key = "add-$editArtworkId",
            factory = viewModelFactory.editFactory(editArtworkId)
        )
    } else {
        viewModel(factory = viewModelFactory)
    }
    val state by viewModel.uiState.collectAsState()
    val allTags by viewModel.allTags.collectAsState()
    val scrollState = rememberScrollState()
    var showDatePicker by remember { mutableStateOf(false) }
    var statusMenuExpanded by remember { mutableStateOf(false) }
    var showTagPicker by remember { mutableStateOf(false) }

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) viewModel.addPickedUris(uris)
    }

    LaunchedEffect(state.savedArtworkId) {
        state.savedArtworkId?.let {
            onSaved(it)
            viewModel.consumeSavedArtworkId(it)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Edit mode is an overlay (bottom bar hidden), so it needs its
        // own back entry. Create mode is a bottom-nav tab and has none.
        if (viewModel.isEditMode && onBackClick != null) {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.Top
        ) {
        Text(
            text = if (viewModel.isEditMode) "编辑作品" else "新建作品",
            style = MaterialTheme.typography.titleLarge,
            fontSize = 22.sp,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
        )

        // --- Images ---
        Text(
            text = "图片",
            style = MaterialTheme.typography.titleMedium,
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (viewModel.isEditMode) {
            val existing = state.existingArtwork
            if (existing != null && existing.images.isNotEmpty()) {
                ImageGrid(
                    models = existing.images.map { File(it.thumbnailPath) },
                    coverIndex = state.coverIndex,
                    onCoverClick = { viewModel.setCoverIndex(it) },
                    onRemoveClick = null
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "点一张图把它设为封面。本轮不支持重新导入或替换图片。",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp
                )
            }
        } else {
            if (state.pickedUris.isNotEmpty()) {
                ImageGrid(
                    models = state.pickedUris,
                    coverIndex = state.coverIndex,
                    onCoverClick = { viewModel.setCoverIndex(it) },
                    onRemoveClick = { viewModel.removePickedUri(it) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "点一张图把它设为封面。",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            OutlinedButton(
                onClick = {
                    pickerLauncher.launch(
                        PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = if (state.pickedUris.isEmpty()) "从相册选择图片" else "继续添加图片")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- Title ---
        OutlinedTextField(
            value = state.title,
            onValueChange = { viewModel.setTitle(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(text = "作品名称") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // --- Creation date ---
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "创作日期",
                style = MaterialTheme.typography.bodyLarge,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { showDatePicker = true }) {
                Text(text = ArtworkDates.formatDay(state.createdAt))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- Duration ---
        OutlinedTextField(
            value = state.durationText,
            onValueChange = { viewModel.setDurationText(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(text = "创作耗时（分钟，可不填）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // --- Status ---
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "作品状态",
                style = MaterialTheme.typography.bodyLarge,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            Box {
                TextButton(onClick = { statusMenuExpanded = true }) {
                    Text(text = state.status.label)
                }
                DropdownMenu(
                    expanded = statusMenuExpanded,
                    onDismissRequest = { statusMenuExpanded = false }
                ) {
                    ArtworkStatus.entries.forEach { status ->
                        DropdownMenuItem(
                            text = { Text(text = status.label) },
                            onClick = {
                                viewModel.setStatus(status)
                                statusMenuExpanded = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- Tags (round 3) ---
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "标签",
                style = MaterialTheme.typography.bodyLarge,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { showTagPicker = true }) {
                Text(text = "选择标签")
            }
        }
        val selectedTags = allTags.filter { state.selectedTagIds.contains(it.id) }
        if (selectedTags.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                selectedTags.forEach { tag ->
                    androidx.compose.material3.AssistChip(
                        onClick = { showTagPicker = true },
                        label = { Text(text = tag.name) }
                    )
                }
            }
        } else {
            Text(
                text = "还没选标签。标签可以按角色、场景、练习、原创、临摹来分。",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- Notes ---
        OutlinedTextField(
            value = state.description,
            onValueChange = { viewModel.setDescription(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(text = "创作笔记（遇到的问题也可以写在这里）") },
            minLines = 2,
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = state.intention,
            onValueChange = { viewModel.setIntention(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(text = "创作初衷") },
            minLines = 2,
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = state.reflection,
            onValueChange = { viewModel.setReflection(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(text = "自己满意的地方") },
            minLines = 2,
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = state.improvement,
            onValueChange = { viewModel.setImprovement(it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(text = "下次改进的方向") },
            minLines = 2,
            shape = RoundedCornerShape(12.dp)
        )

        // --- Error (the form stays filled in) ---
        state.error?.let { error ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- Save ---
        Button(
            onClick = { viewModel.save() },
            enabled = !state.isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (state.isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "保存中…")
            } else {
                Text(text = if (viewModel.isEditMode) "保存修改" else "保存作品")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // --- Date picker dialog ---
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.createdAt
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        viewModel.setCreatedAt(ArtworkDates.startOfDay(millis))
                    }
                    showDatePicker = false
                }) { Text(text = "确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(text = "取消") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // --- Tag picker dialog (round 3) ---
    if (showTagPicker) {
        TagPickerDialog(
            allTags = allTags,
            selectedIds = state.selectedTagIds,
            onSelectionChange = { viewModel.setSelectedTagIds(it) },
            onCreateTag = { viewModel.createTag(it) },
            onDismiss = { showTagPicker = false }
        )
    }
}

/**
 * Three-column grid of selectable images. Tap to choose the cover;
 * the cover gets an accent border and a "封面" badge. [onRemoveClick]
 * is null in edit mode (images cannot be removed in round 2).
 */
@Composable
private fun ImageGrid(
    models: List<Any>,
    coverIndex: Int,
    onCoverClick: (Int) -> Unit,
    onRemoveClick: ((Int) -> Unit)?
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        models.chunked(3).forEachIndexed { chunkIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEachIndexed { rowIndex, model ->
                    val index = chunkIndex * 3 + rowIndex
                    val isCover = index == coverIndex
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onCoverClick(index) }
                            .then(
                                if (isCover) {
                                    Modifier.border(
                                        width = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        AsyncImage(
                            model = model,
                            contentDescription = "作品图片",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (isCover) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(6.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "封面",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                        if (onRemoveClick != null) {
                            IconButton(
                                onClick = { onRemoveClick(index) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "移除",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
                repeat(3 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
