package com.artlog.app.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.artlog.app.data.ArtworkRepository
import com.artlog.app.data.ArtworkStatus
import com.artlog.app.data.ArtworkWithImages
import com.artlog.app.data.Tag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class GallerySort(val label: String) {
    NEWEST("最新"),
    OLDEST("最早"),
    TITLE("标题")
}

data class GalleryUiState(
    val artworks: List<ArtworkWithImages> = emptyList(),
    val totalCount: Int = 0,
    val query: String = "",
    val statusFilter: ArtworkStatus? = null,
    val sort: GallerySort = GallerySort.NEWEST,
    val selectionMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val confirmDeleteIds: Set<String>? = null,
    /** Tag filter (round 3); null means "all tags". */
    val tagFilter: Tag? = null,
    val allTags: List<Tag> = emptyList()
)

/**
 * Gallery state: search, status filter, sort and batch selection are
 * applied in memory over the database Flow. Nothing is faked — an empty
 * database shows an empty gallery.
 */
class GalleryViewModel(private val repository: ArtworkRepository) : ViewModel() {

    private val _query = MutableStateFlow("")
    private val _statusFilter = MutableStateFlow<ArtworkStatus?>(null)
    private val _sort = MutableStateFlow(GallerySort.NEWEST)
    private val _selectionMode = MutableStateFlow(false)
    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    private val _confirmDeleteIds = MutableStateFlow<Set<String>?>(null)
    private val _tagFilter = MutableStateFlow<Tag?>(null)

    val confirmDeleteIds: StateFlow<Set<String>?> = _confirmDeleteIds.asStateFlow()

    /** Artwork ids carrying the selected tag (null = no tag filter). */
    private val tagArtworkIds: StateFlow<List<String>?> =
        _tagFilter.flatMapLatest { tag ->
            if (tag == null) flowOf(null)
            else repository.artworkIdsForTag(tag.id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val uiState: StateFlow<GalleryUiState> = combine(
        repository.allArtworks,
        _query,
        _statusFilter,
        _sort,
        _selectionMode,
        _selectedIds,
        _confirmDeleteIds,
        _tagFilter,
        tagArtworkIds,
        repository.tags
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val all = values[0] as List<ArtworkWithImages>
        val query = values[1] as String
        val status = values[2] as ArtworkStatus?
        val sort = values[3] as GallerySort
        val selectionMode = values[4] as Boolean
        val selected = values[5] as Set<String>
        val confirm = values[6] as Set<String>?
        val tagFilter = values[7] as Tag?
        @Suppress("UNCHECKED_CAST")
        val tagIds = values[8] as List<String>?
        @Suppress("UNCHECKED_CAST")
        val allTags = values[9] as List<Tag>
        var filtered = all
        if (tagIds != null) {
            filtered = filtered.filter { tagIds.contains(it.artwork.id) }
        }
        if (status != null) {
            filtered = filtered.filter { it.artwork.status == status.name }
        }
        if (query.isNotBlank()) {
            filtered = filtered.filter { it.artwork.title.contains(query, ignoreCase = true) }
        }
        filtered = when (sort) {
            GallerySort.NEWEST -> filtered.sortedByDescending { it.artwork.addedAt }
            GallerySort.OLDEST -> filtered.sortedBy { it.artwork.addedAt }
            GallerySort.TITLE -> filtered.sortedBy { it.artwork.title }
        }
        GalleryUiState(
            artworks = filtered,
            totalCount = all.size,
            query = query,
            statusFilter = status,
            sort = sort,
            selectionMode = selectionMode,
            selectedIds = selected,
            confirmDeleteIds = confirm,
            tagFilter = tagFilter,
            allTags = allTags
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        GalleryUiState()
    )

    fun setQuery(query: String) {
        _query.value = query
    }

    fun setStatusFilter(status: ArtworkStatus?) {
        _statusFilter.value = status
    }

    fun setSort(sort: GallerySort) {
        _sort.value = sort
    }

    /** Filter by one tag (round 3); null clears the filter. */
    fun setTagFilter(tag: Tag?) {
        _tagFilter.value = tag
    }

    fun enterSelectionMode(id: String) {
        _selectionMode.value = true
        _selectedIds.value = setOf(id)
    }

    fun toggleSelection(id: String) {
        val current = _selectedIds.value
        _selectedIds.value = if (current.contains(id)) current - id else current + id
        if (_selectedIds.value.isEmpty()) _selectionMode.value = false
    }

    fun exitSelectionMode() {
        _selectionMode.value = false
        _selectedIds.value = emptySet()
    }

    fun requestDelete(ids: Set<String>) {
        if (ids.isNotEmpty()) _confirmDeleteIds.value = ids
    }

    fun dismissDeleteConfirm() {
        _confirmDeleteIds.value = null
    }

    /** Delete after the user confirmed. Clears the selection afterwards. */
    fun confirmDelete() {
        val ids = _confirmDeleteIds.value ?: return
        _confirmDeleteIds.value = null
        exitSelectionMode()
        viewModelScope.launch {
            ids.forEach { repository.deleteArtwork(it) }
        }
    }
}
