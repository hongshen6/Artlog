package com.artlog.app.ui.tags

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.artlog.app.data.Tag

/**
 * Shared tag picker, used by the Add/Edit form and the Detail page.
 *
 * Selection is applied live through [onSelectionChange] as the user
 * taps checkboxes; [onCreateTag] creates a tag (and the caller adds
 * it to the selection). "完成" just dismisses the dialog.
 */
@Composable
fun TagPickerDialog(
    allTags: List<Tag>,
    selectedIds: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onCreateTag: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newTagName by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = "选择标签",
                    style = MaterialTheme.typography.titleLarge,
                    fontSize = 20.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (allTags.isEmpty()) {
                    Text(
                        text = "还没有标签，在下面新建一个吧。",
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                    ) {
                        items(allTags, key = { it.id }) { tag ->
                            val checked = selectedIds.contains(tag.id)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { isChecked ->
                                        onSelectionChange(
                                            if (isChecked) selectedIds + tag.id
                                            else selectedIds - tag.id
                                        )
                                    }
                                )
                                Text(
                                    text = tag.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newTagName,
                        onValueChange = { newTagName = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(text = "新建标签") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    TextButton(
                        onClick = {
                            if (newTagName.isNotBlank()) {
                                onCreateTag(newTagName)
                                newTagName = ""
                            }
                        }
                    ) {
                        Text(text = "新建")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(text = "完成")
                }
            }
        }
    }
}
