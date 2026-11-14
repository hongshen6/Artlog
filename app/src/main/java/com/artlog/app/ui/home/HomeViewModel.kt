package com.artlog.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.artlog.app.data.ArtworkDates
import com.artlog.app.data.ArtworkRepository
import com.artlog.app.data.ArtworkWithImages
import com.artlog.app.data.JournalEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Home screen state — all numbers come from the local Room database.
 * Totals count only real rows; total duration sums only filled-in
 * durations (NULL durations are ignored by the SQL aggregate).
 */
data class HomeUiState(
    val artworkCount: Int = 0,
    val totalDurationMinutes: Int = 0,
    val thisMonthCount: Int = 0,
    val recentWorks: List<ArtworkWithImages> = emptyList(),
    val recentActivity: List<ArtworkWithImages> = emptyList(),
    /**
     * P2 首页“正在进行”：最近的一篇创作记录（手记）。取自现有的
     * journalEntries 流（createdAt 倒序），首条即最新一篇；无手记
     * 时为 null，首页不展示虚构记录。
     */
    val latestJournal: JournalEntry? = null
)

class HomeViewModel(repository: ArtworkRepository) : ViewModel() {
    // NOTE: kotlinx-coroutines 1.7.3 only offers typed combine()
    // transforms for up to 5 flows; with 6 flows the vararg form
    // (Array<Any?>) is used and each element is cast explicitly.
    val uiState: StateFlow<HomeUiState> = combine(
        repository.artworkCount,
        repository.totalDurationMinutes,
        repository.monthCount(ArtworkDates.startOfThisMonth()),
        repository.recentArtworks(6),
        repository.recentlyUpdated(5),
        repository.journalEntries
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        HomeUiState(
            artworkCount = args[0] as Int,
            totalDurationMinutes = args[1] as Int,
            thisMonthCount = args[2] as Int,
            recentWorks = args[3] as List<ArtworkWithImages>,
            recentActivity = args[4] as List<ArtworkWithImages>,
            latestJournal = (args[5] as List<JournalEntry>).firstOrNull()
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        HomeUiState()
    )
}
