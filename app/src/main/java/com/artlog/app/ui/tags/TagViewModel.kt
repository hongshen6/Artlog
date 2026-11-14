package com.artlog.app.ui.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.artlog.app.data.ArtworkRepository
import com.artlog.app.data.Tag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A tag together with how many artworks carry it. */
data class TagWithCount(
    val tag: Tag,
    val artworkCount: Int
)

/**
 * Tag management state: the tag list with per-tag artwork counts,
 * plus create / rename / delete. Deleting a tag only removes the tag
 * and its links — artworks are never deleted.
 */
class TagViewModel(private val repository: ArtworkRepository) : ViewModel() {

    val tagsWithCounts: StateFlow<List<TagWithCount>> = combine(
        repository.tags,
        repository.allCrossRefs
    ) { tags, refs ->
        val counts = refs.groupingBy { it.tagId }.eachCount()
        tags.map { tag -> TagWithCount(tag, counts[tag.id] ?: 0) }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() {
        _error.value = null
    }

    /** Create a tag. A duplicate name is an error here (the picker path
     * reuses the existing tag instead — see [ArtworkRepository.createTag]). */
    fun createTag(rawName: String) {
        val name = rawName.trim()
        if (name.isEmpty()) {
            _error.value = "标签名不能为空。"
            return
        }
        if (tagsWithCounts.value.any { it.tag.name == name }) {
            _error.value = "已经有叫「$name」的标签了。"
            return
        }
        viewModelScope.launch {
            try {
                repository.createTag(name)
            } catch (e: Exception) {
                _error.value = "新建标签失败：${e.message ?: "未知错误"}"
            }
        }
    }

    /** Rename a tag. A duplicate name is an error. */
    fun renameTag(tag: Tag, rawNewName: String) {
        val newName = rawNewName.trim()
        if (newName.isEmpty()) {
            _error.value = "标签名不能为空。"
            return
        }
        if (tagsWithCounts.value.any { it.tag.name == newName && it.tag.id != tag.id }) {
            _error.value = "已经有叫「$newName」的标签了。"
            return
        }
        viewModelScope.launch {
            try {
                repository.renameTag(tag, newName)
            } catch (e: Exception) {
                _error.value = "重命名失败：${e.message ?: "未知错误"}"
            }
        }
    }

    fun deleteTag(tag: Tag) {
        viewModelScope.launch {
            repository.deleteTag(tag.id)
        }
    }
}
