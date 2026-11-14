package com.artlog.app.ui.add

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.artlog.app.data.ArtworkDates
import com.artlog.app.data.ArtworkRepository
import com.artlog.app.data.ArtworkStatus
import com.artlog.app.data.ArtworkWithImages
import com.artlog.app.data.NewArtworkInput
import com.artlog.app.data.Tag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Add / edit artwork form state.
 *
 * - Create mode ([editArtworkId] == null): title, date, duration, status,
 *   notes and picked images. Saving shows a loading state; on failure the
 *   filled-in data is kept and the error is shown.
 * - Edit mode: the existing artwork's metadata is loaded and the same
 *   form edits it. Images cannot be re-imported in round 2; the existing
 *   images are shown read-only and the cover can be switched between them.
 */
data class AddUiState(
    val title: String = "",
    val createdAt: Long = ArtworkDates.startOfToday(),
    val durationText: String = "",
    val status: ArtworkStatus = ArtworkStatus.DRAFT,
    val description: String = "",
    val intention: String = "",
    val reflection: String = "",
    val improvement: String = "",
    /** Newly picked image URIs (create mode). */
    val pickedUris: List<Uri> = emptyList(),
    /** Existing artwork with its images (edit mode), shown read-only. */
    val existingArtwork: ArtworkWithImages? = null,
    val coverIndex: Int = 0,
    val existingCoverPath: String? = null,
    /** Tag ids selected for this artwork (round 3). */
    val selectedTagIds: Set<String> = emptySet(),
    val isSaving: Boolean = false,
    val error: String? = null,
    /** Set on a successful save; the screen navigates to the detail page. */
    val savedArtworkId: String? = null
)

class AddViewModel(
    private val repository: ArtworkRepository,
    private val editArtworkId: String? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddUiState())
    val uiState: StateFlow<AddUiState> = _uiState.asStateFlow()

    /** All tags, for the tag picker (round 3). */
    val allTags: StateFlow<List<Tag>> =
        repository.tags.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    val isEditMode: Boolean = editArtworkId != null

    init {
        if (editArtworkId != null) {
            viewModelScope.launch {
                repository.tagsForArtwork(editArtworkId).collect { tags ->
                    _uiState.value = _uiState.value.copy(
                        selectedTagIds = tags.map { it.id }.toSet()
                    )
                }
            }
            viewModelScope.launch {
                repository.artwork(editArtworkId).collect { loaded ->
                    if (loaded == null) return@collect
                    val artwork = loaded.artwork
                    _uiState.value = _uiState.value.copy(
                        title = artwork.title,
                        createdAt = artwork.createdAt,
                        durationText = artwork.durationMinutes?.toString() ?: "",
                        status = ArtworkStatus.fromName(artwork.status),
                        description = artwork.description,
                        intention = artwork.intention,
                        reflection = artwork.reflection,
                        improvement = artwork.improvement,
                        existingArtwork = loaded,
                        existingCoverPath = artwork.coverPath,
                        coverIndex = loaded.images.indexOfFirst {
                            it.imagePath == artwork.coverPath
                        }.coerceAtLeast(0)
                    )
                }
            }
        }
    }

    fun setTitle(value: String) = update { copy(title = value) }
    fun setCreatedAt(value: Long) = update { copy(createdAt = value) }
    fun setDurationText(value: String) = update {
        copy(durationText = value.filter { it.isDigit() }.take(5))
    }
    fun setStatus(value: ArtworkStatus) = update { copy(status = value) }
    fun setDescription(value: String) = update { copy(description = value) }
    fun setIntention(value: String) = update { copy(intention = value) }
    fun setReflection(value: String) = update { copy(reflection = value) }
    fun setImprovement(value: String) = update { copy(improvement = value) }
    fun clearError() = update { copy(error = null) }

    /**
     * Consume the one-shot save-success signal. Must be called AFTER
     * onSaved() has run: navigate first, then clear, so the id is
     * never lost. Only clears when the stored value is the id that
     * was just consumed, so a newer save can never be wiped.
     *
     * Create mode also resets the form to a fresh blank state (the
     * next artwork starts empty — otherwise the saved title and
     * images would stay and a second tap on save would create a
     * duplicate). Edit mode only clears the signal, so the prefilled
     * data stays intact if the same artwork is edited again.
     * allTags is a separate StateFlow and is unaffected.
     */
    fun consumeSavedArtworkId(consumedId: String) {
        val current = _uiState.value
        if (current.savedArtworkId != consumedId) return
        _uiState.value = if (isEditMode) {
            current.copy(savedArtworkId = null)
        } else {
            AddUiState()
        }
    }

    fun addPickedUris(uris: List<Uri>) = update {
        val merged = (pickedUris + uris).distinct()
        copy(pickedUris = merged)
    }

    fun removePickedUri(index: Int) = update {
        val remaining = pickedUris.filterIndexed { i, _ -> i != index }
        copy(
            pickedUris = remaining,
            coverIndex = coverIndex.coerceAtMost((remaining.size - 1).coerceAtLeast(0))
        )
    }

    fun setCoverIndex(index: Int) = update { copy(coverIndex = index) }

    fun setSelectedTagIds(ids: Set<String>) = update { copy(selectedTagIds = ids) }

    /** Create a tag from the picker and select it right away. */
    fun createTag(name: String) {
        viewModelScope.launch {
            try {
                val tag = repository.createTag(name)
                _uiState.value = _uiState.value.copy(
                    selectedTagIds = _uiState.value.selectedTagIds + tag.id
                )
            } catch (e: Exception) {
                // Blank name: the picker simply stays open.
            }
        }
    }

    private fun update(transform: AddUiState.() -> AddUiState) {
        _uiState.value = _uiState.value.transform()
    }

    /** Save (create or update). On failure the form is kept as-is. */
    fun save() {
        val state = _uiState.value
        if (state.isSaving) return
        if (state.title.isBlank()) {
            _uiState.value = state.copy(error = "请先填写作品名称。")
            return
        }
        if (!isEditMode && state.pickedUris.isEmpty()) {
            _uiState.value = state.copy(error = "请先选择至少一张图片。")
            return
        }
        val duration = state.durationText.toIntOrNull()
        _uiState.value = state.copy(isSaving = true, error = null)
        viewModelScope.launch {
            try {
                val savedId = if (isEditMode) {
                    val current = _uiState.value
                    val loaded = current.existingArtwork
                        ?: throw IllegalStateException("找不到要编辑的作品")
                    val artwork = loaded.artwork.copy(
                        title = current.title.trim(),
                        createdAt = current.createdAt,
                        durationMinutes = duration,
                        status = current.status.name,
                        description = current.description,
                        intention = current.intention,
                        reflection = current.reflection,
                        improvement = current.improvement
                    )
                    repository.updateArtwork(artwork)
                    val cover = loaded.images.getOrNull(current.coverIndex)
                    if (cover != null && cover.imagePath != artwork.coverPath) {
                        repository.updateCover(artwork.id, cover.imagePath)
                    }
                    repository.setArtworkTags(artwork.id, current.selectedTagIds)
                    artwork.id
                } else {
                    val createdId = repository.createArtwork(
                        NewArtworkInput(
                            title = state.title.trim(),
                            createdAt = state.createdAt,
                            durationMinutes = duration,
                            status = state.status,
                            description = state.description,
                            intention = state.intention,
                            reflection = state.reflection,
                            improvement = state.improvement,
                            imageUris = state.pickedUris,
                            coverIndex = state.coverIndex
                        )
                    )
                    repository.setArtworkTags(createdId, state.selectedTagIds)
                    createdId
                }
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    savedArtworkId = savedId
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    error = "保存失败：${e.message ?: "未知错误"}，已填写的内容还在。"
                )
            }
        }
    }
}
