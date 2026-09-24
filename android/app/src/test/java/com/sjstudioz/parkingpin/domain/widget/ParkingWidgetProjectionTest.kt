package com.sjstudioz.parkingpin.domain.widget

import com.sjstudioz.parkingpin.domain.parking.FloorParser
import com.sjstudioz.parkingpin.domain.parking.ParkingLocation
import com.sjstudioz.parkingpin.domain.parking.ParkingRecord
import com.sjstudioz.parkingpin.domain.parking.ParkingSource
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a widget is allowed to know, and what it must never be told
 * (docs/06_LOCAL_DATA_AND_WIDGET_SYNC.md §7a, docs/09_SECURITY_PRIVACY_COMPLIANCE.md).
 */
class ParkingWidgetProjectionTest {

    private val start = 1_700_000_000_000L

    private fun record(
        id: String = "session-1",
        endedAt: Long? = null,
        floorRaw: String? = "B3",
        zone: String? = "A구역",
        spot: String? = "142",
        revision: Int = 4,
        location: ParkingLocation? = null,
    ) = ParkingRecord(
        id = id,
        startedAtMillis = start,
        endedAtMillis = endedAt,
        source = ParkingSource.MANUAL,
        confidenceBucket = null,
        location = location,
        floor = FloorParser.parse(floorRaw),
        zone = zone,
        spot = spot,
        memo = "기둥 옆",
        photoRelativePath = "parking-photos/session-1.jpg",
        createdAtMillis = start,
        updatedAtMillis = start,
        revision = revision,
    )

    @Test
    fun `active record becomes the hero card's three lines`() {
        val projection = ParkingWidgetProjection.of(record(), stepperEntitled = true)

        assertEquals("session-1", projection.sessionId)
        assertEquals(4, projection.revision)
        assertEquals("B3", projection.floorLabel)
        assertEquals("지하 3층", projection.floorSpokenLabel)
        assertEquals("A구역 · 142", projection.zoneSpot)
        assertEquals(start, projection.startedAtMillis)
        assertTrue(projection.isActive)
    }

    @Test
    fun `no active parking is the empty projection`() {
        assertEquals(
            ParkingWidgetProjection.Empty,
            ParkingWidgetProjection.of(null, stepperEntitled = true),
        )
        assertFalse(ParkingWidgetProjection.Empty.isActive)
    }

    @Test
    fun `a completed record projects as nothing parked`() {
        // docs/06 §8 startup repair: a session that ended cannot stay on the widget.
        val projection = ParkingWidgetProjection.of(
            record(endedAt = start + 60_000L),
            stepperEntitled = true,
        )

        assertEquals(ParkingWidgetProjection.Empty, projection)
    }

    @Test
    fun `a record with neither zone nor spot has no supporting line`() {
        val projection = ParkingWidgetProjection.of(
            record(zone = null, spot = "  "),
            stepperEntitled = true,
        )

        assertNull(projection.zoneSpot)
        assertEquals("B3", projection.floorLabel)
    }

    @Test
    fun `free text floor offers no steps`() {
        val projection = ParkingWidgetProjection.of(
            record(floorRaw = "주차타워 옆"),
            stepperEntitled = true,
        )

        assertEquals("주차타워 옆", projection.floorLabel)
        assertFalse(projection.canStepDown)
        assertFalse(projection.canStepUp)
        assertFalse(projection.showsStepper)
    }

    @Test
    fun `the bottom of the ladder cannot step down`() {
        val projection = ParkingWidgetProjection.of(
            record(floorRaw = "B${FloorParser.MAX_LEVEL}"),
            stepperEntitled = true,
        )

        assertFalse(projection.canStepDown)
        assertTrue(projection.canStepUp)
        assertTrue(projection.showsStepper)
    }

    @Test
    fun `the top of the ladder cannot step up`() {
        val projection = ParkingWidgetProjection.of(
            record(floorRaw = "${FloorParser.MAX_LEVEL}F"),
            stepperEntitled = true,
        )

        assertTrue(projection.canStepDown)
        assertFalse(projection.canStepUp)
    }

    @Test
    fun `an unentitled projection never shows a stepper`() {
        val projection = ParkingWidgetProjection.of(record(), stepperEntitled = false)

        // The bounds are still reported — only the entitlement withholds the keys.
        assertTrue(projection.canStepDown)
        assertTrue(projection.canStepUp)
        assertFalse(projection.showsStepper)
    }

    @Test
    fun `the serialized snapshot carries no coordinate, address or photo`() {
        val projection = ParkingWidgetProjection.of(
            record(
                location = ParkingLocation(
                    latitude = 37.566535,
                    longitude = 126.977969,
                    horizontalAccuracyM = 12f,
                    capturedAtMillis = start,
                ),
            ),
            stepperEntitled = true,
        )

        val encoded = Json.encodeToString(projection)

        // The widget state file is readable by anything that can read the app's data
        // directory and is copied by device backup, so the coordinate must never arrive.
        listOf("37.5", "126.9", "latitude", "longitude", "photo", "memo", "기둥").forEach {
            assertFalse("leaked '$it' into $encoded", encoded.contains(it))
        }
    }
}
