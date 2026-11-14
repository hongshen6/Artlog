package com.artlog.app.ui.journal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.artlog.app.data.ArtworkDates
import com.artlog.app.data.JournalEntry
import com.artlog.app.ui.ArtlogViewModelFactory

/**
 * Journal (round 3): a timeline of drawing-session notes, newest
 * first. Each entry records what was thought through, what went
 * wrong, and what got done, and may link to one artwork. Tapping an
 * entry opens the editor; tapping its linked artwork opens the
 * artwork's detail page.
 *
 * [openEntryId] is set by the Detail page when the user taps a linked
 * entry there: the editor opens for that entry, then [onEntryOpened]
 * clears the request.
 */
@Composable
fun JournalScreen(
    viewModelFactory: ArtlogViewModelFactory,
    onArtworkClick: (String) -> Unit,
    openEntryId: String? = null,
    onEntryOpened: () -> Unit = {}
) {
    val viewModel: JournalViewModel = viewModel(factory = viewModelFactory)
    val entries by viewModel.entries.collectAsState()
    val artworks by viewModel.artworks.collectAsState()
    val artworkTitles = artworks.associate { it.artwork.id to it.artwork.title }

    var editingEntry by remember { mutableStateOf<JournalEntry?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    // Open the entry requested from the Detail page, once it is loaded.
    LaunchedEffect(openEntryId, entries) {
        val requested = openEntryId ?: return@LaunchedEffect
        val entry = entries.firstOrNull { it.id == requested } ?: return@LaunchedEffect
        editingEntry = entry
        showEditor = true
        onEntryOpened()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "创作日记",
                style = MaterialTheme.typography.titleLarge,
                fontSize = 22.sp
            )
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = {
                    editingEntry = null
                    showEditor = true
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(text = "写手记")
            }
        }

        if (entries.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                Text(
                    text = "还没有手记",
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "把某次画画时的想法、遇到的问题、完成了什么记下来，\n也可以关联一幅作品。",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries, key = { it.id }) { entry ->
                    JournalRow(
                        entry = entry,
                        linkedArtworkTitle = entry.artworkId?.let { artworkTitles[it] },
                        onClick = {
                            editingEntry = entry
                            showEditor = true
                        },
                        onArtworkClick = onArtworkClick
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }

    if (showEditor) {
        JournalEditorDialog(
            entry = editingEntry,
            artworks = artworks.map { it.artwork.id to it.artwork.title },
            onSave = { title, content, artworkId ->
                viewModel.saveEntry(editingEntry, title, content, artworkId)
                showEditor = false
            },
            onDelete = {
                editingEntry?.let { viewModel.deleteEntry(it) }
                showEditor = false
            },
            onDismiss = { showEditor = false }
        )
    }
}

@Composable
private fun JournalRow(
    entry: JournalEntry,
    linkedArtworkTitle: String?,
    onClick: () -> Unit,
    onArtworkClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Text(
            text = entry.title.ifBlank { "手记" },
            style = MaterialTheme.typography.titleMedium,
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = ArtworkDates.formatDay(entry.createdAt),
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (entry.content.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = entry.content,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (entry.artworkId != null) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = { onArtworkClick(entry.artworkId) },
                modifier = Modifier.padding(0.dp)
            ) {
                Text(
                    text = "关联作品：${linkedArtworkTitle ?: "（作品已删除）"}",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp
                )
            }
        }
    }
}

/**
 * Full-screen editor for one journal entry. Title is optional; the
 * content must not be empty. The artwork picker links the entry to
 * one artwork (or none).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JournalEditorDialog(
    entry: JournalEntry?,
    artworks: List<Pair<String, String>>,
    onSave: (title: String, content: String, artworkId: String?) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(entry?.title ?: "") }
    var content by remember { mutableStateOf(entry?.content ?: "") }
    var selectedArtworkId by remember { mutableStateOf(entry?.artworkId) }
    var artworkMenuExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val selectedTitle = artworks.firstOrNull { it.first == selectedArtworkId }?.second

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭"
                    )
                }
                Text(
                    text = if (entry == null) "写手记" else "编辑手记",
                    style = MaterialTheme.typography.titleLarge,
                    fontSize = 20.sp
                )
                Spacer(modifier = Modifier.weight(1f))
                if (entry != null) {
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "删除手记",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = "标题（可不填）") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = "这次画画时想了什么、遇到什么问题、完成了什么" ) },
                minLines = 8,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "关联作品",
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    TextButton(onClick = { artworkMenuExpanded = true }) {
                        Text(text = selectedTitle ?: "不关联")
                    }
                    DropdownMenu(
                        expanded = artworkMenuExpanded,
                        onDismissRequest = { artworkMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(text = "不关联作品") },
                            onClick = {
                                selectedArtworkId = null
                                artworkMenuExpanded = false
                            }
                        )
                        artworks.forEach { (id, artworkTitle) ->
                            DropdownMenuItem(
                                text = { Text(text = artworkTitle) },
                                onClick = {
                                    selectedArtworkId = id
                                    artworkMenuExpanded = false
                                }
                            )
                        }
                    }
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
            Button(
                onClick = {
                    if (title.isBlank() && content.isBlank()) {
                        error = "请先写点内容。"
                        return@Button
                    }
                    onSave(title, content, selectedArtworkId)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(text = "保存")
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(text = "删除手记") },
            text = { Text(text = "确定要删除这篇手记吗？这个操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) { Text(text = "删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(text = "取消") }
            }
        )
    }
}
