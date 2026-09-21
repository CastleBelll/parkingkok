package kr.parkingpin.app.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import kr.parkingpin.app.R
import kr.parkingpin.app.domain.parking.Elapsed
import kr.parkingpin.app.domain.parking.RelativeDay
import kr.parkingpin.app.domain.parking.RelativeDayCalculator
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

/**
 * The string resource and arguments for [elapsed]. Picks the coarsest unit that still
 * says something useful.
 *
 * Split out of [elapsedText] because the widget renders outside Compose UI and must read
 * the same wording through `Context.getString` (docs/06 §7a: the widget is the hero card
 * of `01-home-main.png`, so "1시간 24분째 주차 중" has to be the same sentence in both).
 */
fun elapsedResource(elapsed: Elapsed): Pair<Int, Array<Any>> = when {
    elapsed.days > 0 -> R.string.elapsed_days to arrayOf(elapsed.days, elapsed.hours)
    elapsed.hours > 0 -> R.string.elapsed_hours to arrayOf(elapsed.hours, elapsed.minutes)
    elapsed.minutes > 0 -> R.string.elapsed_minutes to arrayOf(elapsed.minutes)
    // Under a minute reads better as a sentence than as "0분째 주차 중".
    else -> R.string.elapsed_just_now to emptyArray()
}

/** `1시간 24분째 주차 중`. */
@Composable
fun elapsedText(elapsed: Elapsed): String {
    val (id, args) = elapsedResource(elapsed)
    return stringResource(id, *args)
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
