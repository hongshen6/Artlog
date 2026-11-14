package com.artlog.app.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Date helpers for artwork dates. All display uses the device locale
 * (Chinese) and the device time zone.
 */
object ArtworkDates {
    private val dayFormat = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)
    private val monthDayFormat = SimpleDateFormat("M月d日", Locale.CHINA)

    /** "2026年9月23日" */
    fun formatDay(millis: Long): String = dayFormat.format(Date(millis))

    /** "9月23日" (short form for list rows). */
    fun formatMonthDay(millis: Long): String = monthDayFormat.format(Date(millis))

    /** Start of today, local time. */
    fun startOfToday(): Long = Calendar.getInstance().let { cal ->
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }

    /** Start of the current month, local time. */
    fun startOfThisMonth(): Long = Calendar.getInstance().let { cal ->
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }

    /** Start of the day containing [millis], local time. */
    fun startOfDay(millis: Long): Long = Calendar.getInstance().let { cal ->
        cal.timeInMillis = millis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }
}
