package com.voicepick.photos

import android.text.format.DateUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Date text in the phone's language. */
object DateFormats {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val locale: Locale get() = Locale.getDefault()

    fun dayOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    fun startMillis(day: LocalDate): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()

    /** "Today", "Yesterday", or e.g. "Σάββατο 12 Απριλίου 2024". */
    fun dayHeader(day: LocalDate): String {
        val today = LocalDate.now(zone)
        if (day == today || day == today.minusDays(1)) {
            return DateUtils.getRelativeTimeSpanString(
                startMillis(day), System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS,
            ).toString().replaceFirstChar { it.titlecase(locale) }
        }
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(day)
            .replaceFirstChar { it.titlecase(locale) }
    }

    /** e.g. "12 Απρ 2024". */
    fun short(day: LocalDate): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(day)

    fun range(start: LocalDate, end: LocalDate): String =
        if (start == end) short(start) else "${short(start)} – ${short(end)}"

    /** e.g. "14:32". */
    fun time(millis: Long): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
            .format(Instant.ofEpochMilli(millis).atZone(zone))

    /** e.g. "Απρίλιος 2024". */
    fun monthYear(day: LocalDate): String =
        DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(day).replaceFirstChar { it.titlecase(locale) }
}
