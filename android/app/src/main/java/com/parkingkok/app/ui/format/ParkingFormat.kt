package com.parkingkok.app.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.parkingkok.app.R
import com.parkingkok.app.domain.parking.Elapsed
import com.parkingkok.app.domain.parking.RelativeDay
import com.parkingkok.app.domain.parking.RelativeDayCalculator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Korean copy for the values the domain computes.
 *
 * The arithmetic lives in the domain and is unit-tested there; this file only chooses a
 * string resource, so wording and translation stay out of the domain and out of the
 * screens.
 */

/** `1시간 24분째 주차 중`. Picks the coarsest unit that still says something useful. */
@Composable
fun elapsedText(elapsed: Elapsed): String = when {
    elapsed.days > 0 ->
        stringResource(R.string.elapsed_days, elapsed.days, elapsed.hours)
    elapsed.hours > 0 ->
        stringResource(R.string.elapsed_hours, elapsed.hours, elapsed.minutes)
    elapsed.minutes > 0 ->
        stringResource(R.string.elapsed_minutes, elapsed.minutes)
    // Under a minute reads better as a sentence than as "0분째 주차 중".
    else -> stringResource(R.string.elapsed_just_now)
}

/** `오늘` / `어제` / `9월 13일`. */
@Composable
fun dayText(atMillis: Long, nowMillis: Long): String {
    val locale = currentLocale()
    return when (RelativeDayCalculator.of(atMillis, nowMillis, ZoneId.systemDefault())) {
        RelativeDay.TODAY -> stringResource(R.string.history_day_today)
        RelativeDay.YESTERDAY -> stringResource(R.string.history_day_yesterday)
        RelativeDay.OLDER -> formatted(atMillis, DATE_PATTERN, locale)
    }
}

/** `오후 6:24`, in the device's locale and time zone. */
@Composable
fun timeOfDayText(atMillis: Long): String = formatted(atMillis, TIME_PATTERN, currentLocale())

@Composable
private fun currentLocale(): Locale =
    LocalConfiguration.current.locales[0] ?: Locale.getDefault()

private fun formatted(atMillis: Long, pattern: String, locale: Locale): String =
    DateTimeFormatter.ofPattern(pattern, locale)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(atMillis))

private const val DATE_PATTERN = "M월 d일"
private const val TIME_PATTERN = "a h:mm"
