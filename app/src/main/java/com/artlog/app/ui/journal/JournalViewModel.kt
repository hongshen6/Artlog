package com.artlog.app.ui.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.artlog.app.data.ArtworkRepository
import com.artlog.app.data.ArtworkWithImages
import com.artlog.app.data.JournalEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Journal state: entries newest first, plus the artwork list for the
 * link picker. Saving with an existing entry keeps its id and
 * creation date and bumps updatedAt; saving without one creates it.
 */
class JournalViewModel(private val repository: ArtworkRepository) : ViewModel() {

    val entries: StateFlow<List<JournalEntry>> =
        repository.journalEntries.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    val artworks: StateFlow<List<ArtworkWithImages>> =
        repository.allArtworks.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    fun saveEntry(
        existing: JournalEntry?,
        title: String,
        content: String,
        artworkId: String?
    ) {
        val now = System.currentTimeMillis()
        val entry = if (existing == null) {
            JournalEntry(
                id = UUID.randomUUID().toString(),
                title = title.trim(),
                content = content.trim(),
                createdAt = now,
                updatedAt = now,
                artworkId = artworkId
            )
        } else {
            existing.copy(
                title = title.trim(),
                content = content.trim(),
                updatedAt = now,
                artworkId = artworkId
            )
        }
        viewModelScope.launch {
            repository.saveJournalEntry(entry)
        }
    }

    fun deleteEntry(entry: JournalEntry) {
        viewModelScope.launch {
            repository.deleteJournalEntry(entry.id)
        }
    }
}
