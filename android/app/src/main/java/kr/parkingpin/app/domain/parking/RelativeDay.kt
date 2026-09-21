package kr.parkingpin.app.domain.parking

import java.time.Instant
import java.time.ZoneId

/** How a history row labels its date (design-references/04-history-list.png). */
enum class RelativeDay { TODAY, YESTERDAY, OLDER }

/**
 * Classifies an instant against "now" by calendar day.
 *
 * Calendar day, not elapsed hours: 23:50 yesterday is "어제" twenty minutes later, which
 * is what a person reading the list means by it. That makes the answer depend on the
 * device time zone, so [ZoneId] is a parameter rather than a call to `systemDefault()`
 * buried inside — the same reason the codebase injects [kr.parkingpin.app.core.Clock].
 */
object RelativeDayCalculator {

    fun of(atMillis: Long, nowMillis: Long, zone: ZoneId): RelativeDay {
        val day = Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return when (day) {
            today -> RelativeDay.TODAY
            today.minusDays(1) -> RelativeDay.YESTERDAY
            else -> RelativeDay.OLDER
        }
    }
}
