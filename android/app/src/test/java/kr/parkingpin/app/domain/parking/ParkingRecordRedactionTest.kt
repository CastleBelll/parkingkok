package kr.parkingpin.app.domain.parking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/06_LOCAL_DATA_AND_WIDGET_SYNC.md §1 classes coordinates, floor, zone, spot and memo
 * as sensitive local-only data, and CLAUDE.md forbids putting any of it in a log, an
 * analytics event or a crash custom field.
 *
 * `toString` is where that leaks by accident — one `Log.d("saved $record")` is enough —
 * so it is redacted, and this test is what keeps it that way after someone regenerates the
 * data class.
 */
class ParkingRecordRedactionTest {

    private val record = ParkingRecord(
        id = "record-1",
        startedAtMillis = 1_700_000_000_000L,
        endedAtMillis = null,
        source = ParkingSource.MANUAL,
        confidenceBucket = null,
        location = ParkingLocation(37.123_456_7, 127.987_654_3, 18f, 1_700_000_000_000L),
        floor = FloorParser.parse("B3"),
        zone = "A구역",
        spot = "142",
        memo = "기둥 옆 파란 기둥",
        photoRelativePath = "photos/record-1.jpg",
        createdAtMillis = 1_700_000_000_000L,
        updatedAtMillis = 1_700_000_000_000L,
        revision = 1,
    )

    @Test
    fun `a record never prints where the car is`() {
        val printed = record.toString()

        assertFalse(printed.contains("37.123"))
        assertFalse(printed.contains("127.987"))
        assertFalse(printed.contains("A구역"))
        assertFalse(printed.contains("142"))
        assertFalse(printed.contains("기둥"))
        assertFalse(printed.contains("B3"))
        assertFalse(printed.contains("photos/"))
    }

    @Test
    fun `a record still prints enough to debug with`() {
        val printed = record.toString()

        assertTrue(printed.contains("record-1"))
        assertTrue(printed.contains("MANUAL"))
        assertTrue(printed.contains("active=true"))
    }

    @Test
    fun `a location never prints its coordinates`() {
        val printed = record.location.toString()

        assertFalse(printed.contains("37.123"))
        assertFalse(printed.contains("127.987"))
        assertTrue(printed.contains("18.0"))
    }
}
