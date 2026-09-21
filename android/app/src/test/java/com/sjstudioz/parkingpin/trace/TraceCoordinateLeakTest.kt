package com.sjstudioz.parkingpin.trace

import com.sjstudioz.parkingpin.data.DetectionStateStore
import com.sjstudioz.parkingpin.data.InMemoryPreferencesDataStore
import com.sjstudioz.parkingpin.detection.FakeLocationSessionRegistrar
import com.sjstudioz.parkingpin.detection.FusedLocationSessionController
import com.sjstudioz.parkingpin.detection.MutableTestClock
import com.sjstudioz.parkingpin.domain.detection.MotionEventKind
import com.sjstudioz.parkingpin.domain.detection.MotionDomainEvent
import com.sjstudioz.parkingpin.domain.location.LocationSample
import com.sjstudioz.parkingpin.domain.trace.TraceDeviceInfo
import com.sjstudioz.parkingpin.domain.trace.TraceEventType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The privacy invariant, checked against the bytes that actually reach the disk.
 *
 * A trace file exists to be copied off the device — that is the whole point of §9's
 * `run-as` recovery path. A latitude reaching it would turn the recording into a log of
 * where the user parks, which docs/00_CORE_RULES.md Privacy forbids outright.
 *
 * Unlike `TraceSessionSerializationTest`, which checks the *format*, this drives the real
 * pipeline: [LocationSample] carries coordinates, [FusedLocationSessionController] folds
 * them, and whatever comes out the far end is read back off the filesystem. A redacted
 * `toString` would not have caught a leak here either, because nothing on this path calls
 * one.
 */
class TraceCoordinateLeakTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    private val startMillis = 1_700_000_000_000L

    /** Distinctive enough to be unmistakable in a file, and far enough apart to move. */
    private val firstLatitude = 37.123_456_7
    private val firstLongitude = 127.987_654_3
    private val secondLatitude = 37.124_567_8
    private val secondLongitude = 127.988_765_4

    private class Fixture(
        val directory: File,
        val controller: FusedLocationSessionController,
        val store: FileTraceStore,
    )

    private fun fixture(): Fixture {
        val directory = temporaryFolder.newFolder("traces")
        val traceStore = FileTraceStore(directory)
        val stateStore = DetectionStateStore(InMemoryPreferencesDataStore())
        val recorder = TraceRecorder(
            store = traceStore,
            stateStore = stateStore,
            device = TraceDeviceInfo("SM-G996N", "15 (SDK 35)", "0.1.0 (1)"),
            sessionIdFactory = { "session-under-test" },
        )
        return Fixture(
            directory = directory,
            controller = FusedLocationSessionController(
                store = stateStore,
                registrar = FakeLocationSessionRegistrar(),
                clock = MutableTestClock(epochMillis = startMillis),
                traceRecorder = recorder,
            ),
            store = traceStore,
        )
    }

    private fun encodedTraces(f: Fixture): String =
        f.directory.listFiles().orEmpty().filter { it.isFile }.joinToString("\n") { it.readText() }

    @Test
    fun `no coordinate reaches the trace file, even though the fixes carried them`() = runTest {
        // Arrange — a vehicle session so the bounded capture is live, then two real fixes.
        val f = fixture()
        f.controller.onMotionEvent(
            MotionDomainEvent(MotionEventKind.ENTERED_VEHICLE, startMillis, startMillis),
        )

        // Act
        f.controller.onLocationBatch(
            listOf(
                LocationSample(startMillis, firstLatitude, firstLongitude, 8f, 9.2f),
                LocationSample(startMillis + 15_000L, secondLatitude, secondLongitude, 9f, 8.1f),
            ),
        )

        // Assert — against the bytes on disk, not against the field list.
        val encoded = encodedTraces(f)
        assertTrue("nothing was recorded, so the check proves nothing", encoded.isNotEmpty())
        assertFalse(encoded.contains("37.12"))
        assertFalse(encoded.contains("127.98"))
        assertFalse(encoded.lowercase().contains("latitude"))
        assertFalse(encoded.lowercase().contains("longitude"))
        assertFalse(encoded.contains("\"lat\""))
        assertFalse(encoded.contains("\"lon\""))
    }

    @Test
    fun `the distance between two fixes survives as a scalar`() = runTest {
        // Arrange — §9 keeps `distanceFromPreviousM` precisely because it is the part of a
        // position that is useful and safe: how far, never where.
        val f = fixture()
        f.controller.onMotionEvent(
            MotionDomainEvent(MotionEventKind.ENTERED_VEHICLE, startMillis, startMillis),
        )

        // Act
        f.controller.onLocationBatch(
            listOf(
                LocationSample(startMillis, firstLatitude, firstLongitude, 8f, 9.2f),
                LocationSample(startMillis + 15_000L, secondLatitude, secondLongitude, 9f, 8.1f),
            ),
        )

        // Assert
        val fixes = f.store.list().single().events.filter { it.type == TraceEventType.LOCATION }
        assertEquals(2, fixes.size)
        // The first fix of a recording has nothing to measure from: the reliable fix the
        // checkpoint held belongs to wherever the last trip ended.
        assertEquals(null, fixes[0].distanceFromPreviousM)
        val moved = requireNotNull(fixes[1].distanceFromPreviousM)
        assertTrue("expected a real distance, got $moved", moved > 100.0 && moved < 250.0)
        assertEquals(8f, requireNotNull(fixes[0].accuracy), 0f)
        assertEquals(9.2f, requireNotNull(fixes[0].speed), 0f)
    }
}
