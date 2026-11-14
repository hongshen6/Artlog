package com.artlog.app.ui.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.artlog.app.data.ArtworkDates
import com.artlog.app.data.ArtworkRepository
import com.artlog.app.data.ArtworkStatus
import com.artlog.app.data.YearCount
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar

/**
 * Statistics UI state. Everything is computed from real Room rows:
 * unfilled durations count as 0 (SQL SUM ignores NULLs), and no
 * number is ever estimated or made up.
 */
data class StatisticsUiState(
    val artworkCount: Int = 0,
    val totalDurationMinutes: Int = 0,
    /** Average over all artworks; unfilled durations count as 0. */
    val averageDurationMinutes: Int = 0,
    val statusCounts: Map<ArtworkStatus, Int> = emptyMap(),
    /** Monthly new-artwork counts, keyed "yyyy-MM". */
    val monthlyCounts: Map<String, Int> = emptyMap(),
    val yearlyCompletions: List<YearCount> = emptyList(),
    /** Daily new-artwork counts for the heatmap, keyed "yyyy-MM-dd". */
    val dailyCounts: Map<String, Int> = emptyMap()
)

class StatisticsViewModel(repository: ArtworkRepository) : ViewModel() {

    /** First day of the heatmap window: 182 days ending today. */
    private val heatmapSinceMillis: Long = run {
        val cal = Calendar.getInstance()
        cal.timeInMillis = ArtworkDates.startOfToday()
        cal.add(Calendar.DAY_OF_YEAR, -181)
        cal.timeInMillis
    }

    val uiState: StateFlow<StatisticsUiState> = combine(
        repository.artworkCount,
        repository.totalDurationMinutes,
        repository.statusCounts(),
        repository.monthlyCounts(),
        repository.yearlyCompletions(),
        repository.dailyCounts(heatmapSinceMillis)
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val count = values[0] as Int
        @Suppress("UNCHECKED_CAST")
        val totalDuration = values[1] as Int
        @Suppress("UNCHECKED_CAST")
        val statusRows = values[2] as List<com.artlog.app.data.StatusCount>
        @Suppress("UNCHECKED_CAST")
        val monthRows = values[3] as List<com.artlog.app.data.MonthCount>
        @Suppress("UNCHECKED_CAST")
        val yearRows = values[4] as List<YearCount>
        @Suppress("UNCHECKED_CAST")
        val dayRows = values[5] as List<com.artlog.app.data.DayCount>

        val statusMap = ArtworkStatus.entries.associateWith { status ->
            statusRows.firstOrNull { it.status == status.name }?.count ?: 0
        }
        StatisticsUiState(
            artworkCount = count,
            totalDurationMinutes = totalDuration,
            averageDurationMinutes = if (count > 0) totalDuration / count else 0,
            statusCounts = statusMap,
            monthlyCounts = monthRows.associate { it.month to it.count },
            yearlyCompletions = yearRows,
            dailyCounts = dayRows.associate { it.day to it.count }
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        StatisticsUiState()
    )
}
