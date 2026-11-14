package com.artlog.app.ui.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.artlog.app.data.ArtworkStatus
import com.artlog.app.ui.ArtlogViewModelFactory
import com.artlog.app.ui.theme.ArtlogAccent
import com.artlog.app.ui.theme.ArtlogBorder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Statistics (round 3). Every number is computed from real Room rows:
 * total and average duration (unfilled durations count as 0, never
 * estimated), monthly new-artwork counts, status breakdown, yearly
 * completions, and a 26-week creation calendar. With no artworks at
 * all the page says so honestly instead of showing a fake chart.
 */
@Composable
fun StatisticsScreen(viewModelFactory: ArtlogViewModelFactory) {
    val viewModel: StatisticsViewModel = viewModel(factory = viewModelFactory)
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Text(
            text = "统计",
            style = MaterialTheme.typography.titleLarge,
            fontSize = 22.sp,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
        )

        if (state.artworkCount == 0) {
            Spacer(modifier = Modifier.height(64.dp))
            Text(
                text = "还没有数据",
                style = MaterialTheme.typography.bodyLarge,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "添加一幅作品后，这里会按真实记录统计\n作品数量、创作时长、月度新增与创作日历。",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            return
        }

        // Overview: totals.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                label = "作品总数",
                value = "${state.artworkCount} 幅",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = "累计创作时间",
                value = formatDuration(state.totalDurationMinutes),
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = "平均时长",
                value = formatDuration(state.averageDurationMinutes),
                note = "未填按 0 计",
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(28.dp))
        SectionTitle(text = "状态分布")
        ArtworkStatus.entries.forEach { status ->
            val count = state.statusCounts[status] ?: 0
            StatRow(
                label = status.label,
                countText = "$count 幅",
                fraction = if (state.artworkCount > 0) {
                    count.toFloat() / state.artworkCount
                } else 0f
            )
        }

        Spacer(modifier = Modifier.height(28.dp))
        SectionTitle(text = "每月作品数量")
        val months = lastTwelveMonths()
        val maxMonth = months.maxOfOrNull { state.monthlyCounts[it.first] ?: 0 } ?: 0
        months.forEach { (key, label) ->
            val count = state.monthlyCounts[key] ?: 0
            StatRow(
                label = label,
                countText = "$count 幅",
                fraction = if (maxMonth > 0) count.toFloat() / maxMonth else 0f
            )
        }

        Spacer(modifier = Modifier.height(28.dp))
        SectionTitle(text = "年度完成")
        val currentYear = Calendar.getInstance().get(Calendar.YEAR).toString()
        val thisYear = state.yearlyCompletions
            .firstOrNull { it.year == currentYear }?.count ?: 0
        Text(
            text = "今年已完成 $thisYear 幅",
            style = MaterialTheme.typography.bodyLarge,
            fontSize = 16.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        state.yearlyCompletions.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${row.year} 年",
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${row.count} 幅",
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
        SectionTitle(text = "创作日历")
        Text(
            text = "过去 26 周，每天新增的作品数",
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        CreationHeatmap(dailyCounts = state.dailyCounts)
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "少",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(6.dp))
            listOf(0, 1, 2, 4).forEach { count ->
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(heatColor(count))
                )
                Spacer(modifier = Modifier.width(3.dp))
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "多",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontSize = 16.sp,
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier, note: String? = null) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontSize = 18.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatRow(label: String, countText: String, fraction: Float) {
    Column(modifier = Modifier.padding(vertical = 5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = countText,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(ArtlogBorder)
        ) {
            if (fraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0.04f, 1f))
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(ArtlogAccent)
                )
            }
        }
    }
}

/** "2026-11" to "2026年11月", oldest first, for the last 12 months. */
private fun lastTwelveMonths(): List<Pair<String, String>> {
    val keyFormat = SimpleDateFormat("yyyy-MM", Locale.US)
    val labelFormat = SimpleDateFormat("yyyy年M月", Locale.CHINA)
    val cal = Calendar.getInstance()
    cal.add(Calendar.MONTH, -11)
    return (0 until 12).map {
        val key = keyFormat.format(cal.time)
        val label = labelFormat.format(cal.time)
        cal.add(Calendar.MONTH, 1)
        key to label
    }
}

private fun formatDuration(minutes: Int): String {
    return if (minutes < 60) {
        "$minutes 分钟"
    } else {
        val hours = minutes / 60
        val rest = minutes % 60
        if (rest == 0) "${hours}小时" else "${hours}小时${rest}分钟"
    }
}

/**
 * 26-week creation calendar: 7 rows (Monday first) by week columns.
 * One cell per day; color deepens with the day's artwork count.
 * Days before the 182-day window (first-week padding) are blank.
 */
@Composable
private fun CreationHeatmap(dailyCounts: Map<String, Int>) {
    val weeks = buildHeatmapWeeks(dailyCounts)
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        weeks.forEach { week ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                week.forEach { day ->
                    if (day == null) {
                        Spacer(modifier = Modifier.size(10.dp))
                    } else {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(heatColor(day.count))
                        )
                    }
                }
            }
        }
    }
}

private data class HeatDay(val count: Int)

private fun buildHeatmapWeeks(dailyCounts: Map<String, Int>): List<List<HeatDay?>> {
    val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val end = Calendar.getInstance()
    val start = (end.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -181) }
    val days = mutableListOf<HeatDay>()
    val cursor = start.clone() as Calendar
    while (!cursor.after(end)) {
        val key = dayFormat.format(cursor.time)
        days.add(HeatDay(dailyCounts[key] ?: 0))
        cursor.add(Calendar.DAY_OF_YEAR, 1)
    }
    // Monday-first alignment: pad the first week with blanks.
    val firstWeekday = (start.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Mon = 0
    val cells = MutableList<HeatDay?>(firstWeekday) { null }
    cells.addAll(days)
    while (cells.size % 7 != 0) cells.add(null)
    return cells.chunked(7)
}

private fun heatColor(count: Int) = when {
    count <= 0 -> ArtlogBorder
    count == 1 -> ArtlogAccent.copy(alpha = 0.35f)
    count <= 3 -> ArtlogAccent.copy(alpha = 0.65f)
    else -> ArtlogAccent
}
