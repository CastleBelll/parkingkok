import Foundation
import Testing
@testable import ParkingPin

/// `docs/05_PARKING_DETECTION_ENGINE.md` §11 and §11a: driving away ends the parking.
///
/// The mirror of Android's `AutoEndParkingTest`. Both platforms had the same gap in
/// different shapes — Android had the states with no effect behind them, iOS had neither —
/// so the assertions here are about **what the engine emits**, not only where it lands.
@Suite("§11 departure")
struct DepartureTests {
    private let t0 = TestTime.offset(0)

    private func at(_ seconds: TimeInterval) -> Date { t0.addingTimeInterval(seconds) }

    /// `IDLE → … → PARKED`: a confirmed candidate is the only way in.
    private func parkedEngine() async -> ParkingDetectionEngine {
        let engine = ParkingDetectionEngine()
        _ = await engine.restore(nil, seedIfAbsent: false, now: t0)
        _ = await engine.handle(.vehicleEnter(at: t0))
        _ = await engine.handle(.timerTick(at: at(DrivingConfirmationPolicy.minimumVehicleDuration)))
        _ = await engine.handle(.vehicleExit(at: at(600)))
        _ = await engine.handle(.walkingEnter(at: at(630)))
        _ = await engine.handle(.userConfirmedParking(at: at(660)))
        return engine
    }

    private func endedAt(_ effects: [DetectionEffect]) -> Date? {
        effects.compactMap {
            if case let .endActiveParking(at) = $0 { return at }
            return nil
        }.first
    }

    /// A fix `north` metres up the same meridian, fast enough to read as driving.
    private func fix(at date: Date, north: Double) -> LocationFix {
        LocationFix(
            timestamp: date,
            latitude: 37.5 + north / 111_320,
            longitude: 127.0,
            horizontalAccuracy: 8,
            speed: 12
        )
    }

    @Test("Driving away ends the parking, at the moment it pulled away")
    func drivingAwayEndsTheParking() async throws {
        // Arrange
        let engine = await parkedEngine()
        #expect(await engine.state == .parked)

        // Act — get in, then cover §11's distance and §7's confirmation.
        let departAt = at(3600)
        _ = await engine.handle(.vehicleEnter(at: departAt))
        var effects: [DetectionEffect] = []
        for step in 1 ... 12 {
            effects += await engine.handle(
                .location(fix(at: departAt.addingTimeInterval(Double(step) * 30), north: Double(step) * 300))
            )
        }

        // Assert — the record is closed, and closed at the moment §11's bars were cleared
        // rather than whenever §7's guard finally agreed minutes later.
        #expect(await engine.state == .driving)
        let ended = try #require(endedAt(effects), "driving away must close the record")
        #expect(ended >= departAt, "the parking cannot end before the car was got into")
        // The guard needs minutes of driving; the end time must predate the moment it
        // finally agreed, or the record closes somewhere down the road.
        let confirmedAt = departAt.addingTimeInterval(12 * 30)
        #expect(ended < confirmedAt, "the end time is when it pulled away, not when we were sure")
    }

    @Test("Sitting in the parked car ends nothing")
    func sittingStillEndsNothing() async throws {
        // §11: "if uncertain -> suggestion, not destructive silent end." A phone that woke
        // up in a parked car clears the 90-second bar on its own and never moves 500 m.
        let engine = await parkedEngine()

        let departAt = at(3600)
        _ = await engine.handle(.vehicleEnter(at: departAt))
        let effects = await engine.handle(
            .timerTick(at: departAt.addingTimeInterval(DrivingConfirmationPolicy.minimumVehicleDuration + 60))
        )

        #expect(await engine.state == .parked, "the distance bar is what keeps it here")
        #expect(endedAt(effects) == nil, "ending a parking the user is still inside is the unrecoverable move")
    }

    @Test("A departure that turns back ends nothing")
    func abandonedDepartureEndsNothing() async throws {
        // Past §11's bars — 90 s and 500 m — but short of §7's, which wants 120 s or 800 m.
        // That gap is the whole reason `DEPARTURE_CANDIDATE` is a state and not a
        // pass-through, and these numbers sit inside it deliberately: 100 s and 600 m.
        //
        // Four fixes, not three: the first one only sets the anchor, so `n` fixes measure
        // `n - 1` legs.
        let engine = await parkedEngine()

        let departAt = at(3600)
        _ = await engine.handle(.vehicleEnter(at: departAt))
        var effects: [DetectionEffect] = []
        for step in 1 ... 4 {
            effects += await engine.handle(
                .location(fix(at: departAt.addingTimeInterval(Double(step) * 25), north: Double(step) * 200))
            )
        }
        #expect(await engine.state == .departureCandidate, "the control: §11's bars were cleared")

        // Then the vehicle ends before it ever became a drive.
        effects += await engine.handle(.vehicleExit(at: departAt.addingTimeInterval(110)))

        #expect(await engine.state == .parked)
        #expect(endedAt(effects) == nil)
    }
}
