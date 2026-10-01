package com.sjstudioz.parkingpin.domain.detection

import com.sjstudioz.parkingpin.domain.location.LocationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * docs/05 §11d: a ride in someone else's car is not a departure. iOS twin: `PassengerRideTests`.
 */
class PassengerRideTest {

    private var nextId = 0
    private val engine = ParkingDetectionEngine { "candidate-${nextId++}" }

    private fun at(seconds: Long) = T0 + seconds * 1_000L

    private fun fix(seconds: Long, north: Double, accuracyM: Float = 10f, speedMps: Float? = 14f) = LocationSample(
        atMillis = at(seconds),
        latitude = ORIGIN_LATITUDE + north / METERS_PER_DEGREE_LATITUDE,
        longitude = ORIGIN_LONGITUDE,
        horizontalAccuracyM = accuracyM,
        speedMps = speedMps,
    )

    private val spot = ReliableLocation(ORIGIN_LATITUDE, ORIGIN_LONGITUDE, horizontalAccuracyM = 10f, capturedAtMillis = T0)

    private fun DetectionEngineState.handle(event: DetectionEvent): DetectionEngineState = engine.handle(this, event).state

    private fun parkedHere(location: ReliableLocation? = spot) =
        DetectionEngineState.startingIn(DetectionState.IDLE, T0).handle(DetectionEvent.UserSavedParking(T0, location))

    /** A drive that begins at [startNorth] metres from the spot and heads further north. */
    private fun ride(from: DetectionEngineState, startNorth: Double): List<EngineStep> {
        var state = from.handle(DetectionEvent.VehicleEnter(at(600)))
        val steps = mutableListOf<EngineStep>()
        var north = startNorth
        for (seconds in 660L..1_200L step 60) {
            val step = engine.handle(state, DetectionEvent.Location(fix(seconds, north)))
            steps += step
            state = step.state
            north += 840.0
        }
        return steps
    }

    // ── The policy ──────────────────────────────────────────────────────────────────

    @Test
    fun `a first fix near the spot is the parked car leaving`() {
        assertFalse(PassengerRidePolicy.isElsewhere(fix(60, north = 150.0), spot, vehicleStartedAtMillis = at(0)))
    }

    @Test
    fun `a first fix kilometres away a minute in is another car`() {
        assertTrue(PassengerRidePolicy.isElsewhere(fix(60, north = 4_000.0), spot, vehicleStartedAtMillis = at(0)))
    }

    @Test
    fun `the allowance grows with the time the car could have been driving`() {
        // 4 km at five minutes is a car that left the spot and drove — not proof of anything.
        assertFalse(PassengerRidePolicy.isElsewhere(fix(300, north = 4_000.0), spot, vehicleStartedAtMillis = at(0)))
    }

    @Test
    fun `a coarse fix widens the allowance instead of convicting`() {
        assertFalse(PassengerRidePolicy.isElsewhere(fix(60, north = 1_600.0, accuracyM = 1_500f), spot, at(0)))
    }

    // ── The engine ──────────────────────────────────────────────────────────────────

    @Test
    fun `a ride that starts across town proposes nothing and stays parked`() {
        // Act
        val steps = ride(parkedHere(), startNorth = 4_000.0)

        // Assert
        assertTrue(steps.none { step -> step.effects.any { it is DetectionEffect.ProposeParkingEnd } })
        assertEquals(DetectionState.PARKED, steps.last().state.state)
        assertNull("the capture goes with the session", steps.last().state.session)
    }

    @Test
    fun `the same ride from the spot is the user's departure`() {
        val steps = ride(parkedHere(), startNorth = 100.0)

        assertTrue(steps.any { step -> step.effects.any { it is DetectionEffect.ProposeParkingEnd } })
    }

    @Test
    fun `a parking saved without a location keeps today's behaviour`() {
        val steps = ride(parkedHere(location = null), startNorth = 4_000.0)

        assertTrue(steps.any { step -> step.effects.any { it is DetectionEffect.ProposeParkingEnd } })
    }

    @Test
    fun `more vehicle evidence from the same ride opens nothing`() {
        // Arrange — judged someone else's.
        val judged = ride(parkedHere(), startNorth = 4_000.0).last().state

        // Act
        val more = judged.handle(DetectionEvent.VehicleEnter(at(900)))

        // Assert
        assertNull(more.session)
        assertEquals(DetectionState.PARKED, more.state)
    }

    @Test
    fun `after the ride ends the user's own departure is judged afresh`() {
        // Arrange
        val judged = ride(parkedHere(), startNorth = 4_000.0).last().state
            .handle(DetectionEvent.VehicleExit(at(1_300)))
            .handle(DetectionEvent.WalkingEnter(at(1_301)))
        assertNull(judged.passengerRideLastVehicleAtMillis)

        // Act — back at the car, driving off.
        var state = judged.handle(DetectionEvent.VehicleEnter(at(5_000)))
        var proposed = false
        var north = 50.0
        for (seconds in 5_060L..5_600L step 60) {
            val step = engine.handle(state, DetectionEvent.Location(fix(seconds, north)))
            proposed = proposed || step.effects.any { it is DetectionEffect.ProposeParkingEnd }
            state = step.state
            north += 840.0
        }

        // Assert
        assertTrue(proposed)
    }

    private companion object {
        const val T0 = 1_700_000_000_000L
        const val ORIGIN_LATITUDE = 37.5665
        const val ORIGIN_LONGITUDE = 126.9780
        const val METERS_PER_DEGREE_LATITUDE = 6_371_008.8 * PI / 180
    }
}
