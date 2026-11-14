package com.artlog.app.ui.tags

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.artlog.app.data.Tag
import com.artlog.app.ui.ArtlogViewModelFactory

/**
 * Tag management (round 3). Reached from the Gallery's tag filter row.
 *
 * Create / rename / delete tags. Deleting a tag only removes the tag
 * and its links — the artworks themselves are never deleted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagManagementScreen(
    onBackClick: () -> Unit,
    viewModelFactory: ArtlogViewModelFactory
) {
    val viewModel: TagViewModel = viewModel(factory = viewModelFactory)
    val tagsWithCounts by viewModel.tagsWithCounts.collectAsState()
    val error by viewModel.error.collectAsState()
    var newTagName by remember { mutableStateOf("") }
    var renamingTag by remember { mutableStateOf<Tag?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deletingTag by remember { mutableStateOf<Tag?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(text = "标签管理", fontSize = 20.sp) },
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            // Create a tag.
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newTagName,
                    onValueChange = {
                        newTagName = it
                        viewModel.clearError()
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text(text = "新建标签，例如：角色、场景、练习") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        viewModel.createTag(newTagName)
                        newTagName = ""
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(text = "新建")
                }
            }

            error?.let { message ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (tagsWithCounts.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(48.dp))
                    Text(
                        text = "还没有标签",
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "标签可以按角色、场景、练习、原创、临摹来分，\n建好后就能在作品库里按标签筛选。",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn {
                    items(tagsWithCounts, key = { it.tag.id }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.tag.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "${item.artworkCount} 幅作品",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = {
                                renamingTag = item.tag
                                renameText = item.tag.name
                                viewModel.clearError()
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = "重命名"
                                )
                            }
                            IconButton(onClick = { deletingTag = item.tag }) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "删除标签",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Rename dialog.
    val tagToRename = renamingTag
    if (tagToRename != null) {
        AlertDialog(
            onDismissRequest = { renamingTag = null },
            title = { Text(text = "重命名标签") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.renameTag(tagToRename, renameText)
                    renamingTag = null
                }) { Text(text = "保存") }
            },
            dismissButton = {
                TextButton(onClick = { renamingTag = null }) { Text(text = "取消") }
            }
        )
    }

    // Delete confirmation.
    val tagToDelete = deletingTag
    if (tagToDelete != null) {
        AlertDialog(
            onDismissRequest = { deletingTag = null },
            title = { Text(text = "删除标签") },
            text = {
                Text(
                    text = "确定要删除「${tagToDelete.name}」吗？作品本身不会被删除，" +
                        "只是去掉这个标签。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTag(tagToDelete)
                    deletingTag = null
                }) { Text(text = "删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingTag = null }) { Text(text = "取消") }
            }
        )
    }
}
