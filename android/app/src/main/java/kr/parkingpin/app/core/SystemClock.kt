package kr.parkingpin.app.core

import android.os.SystemClock as AndroidSystemClock

/** Production [Clock] backed by the platform clocks. */
object SystemClock : Clock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()

    override fun elapsedRealtimeNanos(): Long = AndroidSystemClock.elapsedRealtimeNanos()
}
