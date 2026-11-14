package com.artlog.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.artlog.app.data.ArtworkRepository
import com.artlog.app.data.ArtworkWithImages
import com.artlog.app.data.JournalEntry
import com.artlog.app.data.Tag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Detail screen state (P2 gallery redesign).
 *
 * The ViewModel holds the artwork the user is looking at
 * ([currentArtworkId]) plus the ordered id list of the Gallery list
 * the user came in from ([orderedIds]). Prev/next walks that list
 * only — it never falls back to the whole library, so switching
 * artworks inside the detail page can never jump to a work the user
 * did not see in the list. When the detail page was opened from
 * somewhere without a list (Home timeline, Journal, Add-after-save),
 * [orderedIds] is empty and prev/next are simply unavailable.
 *
 * All artwork-derived flows (artwork, tags, linked journal entries)
 * follow [currentArtworkId] through flatMapLatest, so switching
 * artworks swaps every piece of displayed data together — no stale
 * title, tags or journal rows from the previous artwork can linger.
 *
 * Deletion is done through the repository so database rows and files
 * are removed together. Unlike the pre-P2 version, [confirmDelete]
 * catches failures and surfaces them as [deleteError] instead of
 * silently doing nothing.
 */
class DetailViewModel(
    private val repository: ArtworkRepository,
    initialArtworkId: String,
    initialArtworkIds: List<String> = emptyList()
) : ViewModel() {

    private val _currentArtworkId = MutableStateFlow(initialArtworkId)
    val currentArtworkId: StateFlow<String> = _currentArtworkId.asStateFlow()

    private val _orderedIds = MutableStateFlow(initialArtworkIds)
    val orderedIds: StateFlow<List<String>> = _orderedIds.asStateFlow()

    /**
     * Open (or re-open) an artwork, e.g. after returning from the
     * edit page, or when a new detail page is opened while this
     * ViewModel instance is reused. The list is replaced as well so
     * prev/next always matches the list the user just came from.
     */
    fun openArtwork(artworkId: String, artworkIds: List<String>) {
        _orderedIds.value = artworkIds
        if (_currentArtworkId.value != artworkId) {
            _currentArtworkId.value = artworkId
        }
    }

    val artwork: StateFlow<ArtworkWithImages?> =
        _currentArtworkId.flatMapLatest { repository.artwork(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Tags on the current artwork (round 3), observed live. */
    val tags: StateFlow<List<Tag>> =
        _currentArtworkId.flatMapLatest { repository.tagsForArtwork(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All tags, for the tag picker. */
    val allTags: StateFlow<List<Tag>> =
        repository.tags
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Journal entries linked to the current artwork (round 3). */
    val linkedEntries: StateFlow<List<JournalEntry>> =
        _currentArtworkId.flatMapLatest { repository.journalByArtwork(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hasPrevious: StateFlow<Boolean> =
        combine(_orderedIds, _currentArtworkId) { ids, current ->
            DetailNavigationLogic.hasPrevious(ids, current)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val hasNext: StateFlow<Boolean> =
        combine(_orderedIds, _currentArtworkId) { ids, current ->
            DetailNavigationLogic.hasNext(ids, current)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** "3 / 12" while inside a list, null when there is no list. */
    val positionLabel: StateFlow<String?> =
        combine(_orderedIds, _currentArtworkId) { ids, current ->
            DetailNavigationLogic.positionLabel(ids, current)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun goToPrevious() {
        val prev = DetailNavigationLogic.previousId(
            _orderedIds.value, _currentArtworkId.value
        )
        if (prev != null) _currentArtworkId.value = prev
    }

    fun goToNext() {
        val next = DetailNavigationLogic.nextId(
            _orderedIds.value, _currentArtworkId.value
        )
        if (next != null) _currentArtworkId.value = next
    }

    private val _showDeleteConfirm = MutableStateFlow(false)
    val showDeleteConfirm: StateFlow<Boolean> = _showDeleteConfirm.asStateFlow()

    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    /** Delete failure message, if the confirmed delete threw. */
    private val _deleteError = MutableStateFlow<String?>(null)
    val deleteError: StateFlow<String?> = _deleteError.asStateFlow()

    fun requestDelete() {
        _showDeleteConfirm.value = true
    }

    fun dismissDelete() {
        _showDeleteConfirm.value = false
    }

    fun dismissDeleteError() {
        _deleteError.value = null
    }

    fun confirmDelete() {
        _showDeleteConfirm.value = false
        val id = _currentArtworkId.value
        viewModelScope.launch {
            try {
                repository.deleteArtwork(id)
                _deleted.value = true
            } catch (e: Exception) {
                // Pre-P2 this failed silently (no try/catch at all):
                // the dialog closed and nothing happened. Surface it.
                _deleteError.value = e.message ?: "删除失败，请稍后再试。"
            }
        }
    }

    /** Replace the current artwork's tags (round 3). */
    fun setTags(tagIds: Set<String>) {
        val id = _currentArtworkId.value
        viewModelScope.launch {
            repository.setArtworkTags(id, tagIds)
        }
    }

    /** Create a tag from the picker and attach it to the current artwork. */
    fun createTag(name: String) {
        val id = _currentArtworkId.value
        viewModelScope.launch {
            try {
                val tag = repository.createTag(name)
                val currentIds = tags.value.map { it.id }.toSet()
                repository.setArtworkTags(id, currentIds + tag.id)
            } catch (e: Exception) {
                // Blank name: the picker simply stays open.
            }
        }
    }
}
