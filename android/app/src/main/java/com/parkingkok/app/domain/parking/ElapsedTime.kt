package com.parkingkok.app.domain.parking

/**
 * How long a car has been parked, split into the units the UI shows
 * (docs/10_DESIGN_UX_SPEC.md §6 puts elapsed third in the home hierarchy).
 *
 * The struct carries numbers only. The Korean wording — `1시간 24분째 주차 중` — is built in
 * the UI layer from string resources, so this stays unit-testable on the JVM and
 * translatable later.
 */
data class Elapsed(
    val totalMinutes: Long,
    val days: Long,
    val hours: Long,
    val minutes: Long,
)

/** Computes [Elapsed] from two wall-clock instants. */
object ElapsedTime {

    private const val MILLIS_PER_MINUTE = 60_000L
    private const val MINUTES_PER_HOUR = 60L
    private const val HOURS_PER_DAY = 24L

    /**
     * Time between [startedAtMillis] and [nowMillis], floored to the minute.
     *
     * A negative span collapses to zero rather than counting backwards. The device clock
     * can move — an NTP correction, a user changing the time, a record restored from a
     * backup taken on another device — and "0분째 주차 중" is a harmless reading where
     * "-3시간" would look like a bug in the app.
     */
    fun since(startedAtMillis: Long, nowMillis: Long): Elapsed {
        val totalMinutes = ((nowMillis - startedAtMillis) / MILLIS_PER_MINUTE).coerceAtLeast(0L)
        val totalHours = totalMinutes / MINUTES_PER_HOUR
        return Elapsed(
            totalMinutes = totalMinutes,
            days = totalHours / HOURS_PER_DAY,
            hours = totalHours % HOURS_PER_DAY,
            minutes = totalMinutes % MINUTES_PER_HOUR,
        )
    }
}
