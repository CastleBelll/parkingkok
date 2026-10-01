import Foundation
import Testing
@testable import ParkingPin

/// docs/05 §11d: a ride in someone else's car is not a departure. Android twin:
/// `PassengerRideTest`.
@Suite("§11d A ride in someone else's car")
struct PassengerRideTests {
    private let t0 = TestTime.offset(0)

    private func at(_ seconds: TimeInterval) -> Date {
        t0.addingTimeInterval(seconds)
    }

    private var spot: LastReliableLocation {
        TestGeo.fix(at: t0, accuracy: 10).reliableLocation
    }

    private func parkedEngine(location: LastReliableLocation?) async -> ParkingDetectionEngine {
        let engine = ParkingDetectionEngine()
        _ = await engine.restore(nil, seedIfAbsent: false, now: t0)
        _ = await engine.handle(.userSavedParking(at: t0, location: location))
        return engine
    }

    /// A drive that begins `startNorth` metres from the spot and heads further north.
    private func ride(_ engine: ParkingDetectionEngine, startNorth: Double, from start: TimeInterval = 600) async -> [DetectionEffect] {
        var effects = await engine.handle(.vehicleEnter(at: at(start)))
        var north = startNorth
        for offset in stride(from: 60.0, through: 600, by: 60) {
            effects += await engine.handle(.location(TestGeo.fix(at: at(start + offset), metersNorth: north, speed: 14)))
            north += 840
        }
        return effects
    }

    private func proposes(_ effects: [DetectionEffect]) -> Bool {
        effects.contains { if case .proposeParkingEnd = $0 { true } else { false } }
    }

    // MARK: - The policy

    @Test("A first fix near the spot is the parked car leaving")
    func nearIsOwnCar() {
        #expect(!PassengerRidePolicy.isElsewhere(fix: TestGeo.fix(at: at(60), metersNorth: 150), parked: spot, vehicleStartedAt: t0))
    }

    @Test("A first fix kilometres away a minute in is another car")
    func farIsAnotherCar() {
        #expect(PassengerRidePolicy.isElsewhere(fix: TestGeo.fix(at: at(60), metersNorth: 4_000), parked: spot, vehicleStartedAt: t0))
    }

    @Test("The allowance grows with the time the car could have been driving")
    func allowanceGrows() {
        #expect(!PassengerRidePolicy.isElsewhere(fix: TestGeo.fix(at: at(300), metersNorth: 4_000), parked: spot, vehicleStartedAt: t0))
    }

    @Test("A coarse fix widens the allowance instead of convicting")
    func coarseFixWidens() {
        let coarse = TestGeo.fix(at: at(60), metersNorth: 1_600, accuracy: 1_500)
        #expect(!PassengerRidePolicy.isElsewhere(fix: coarse, parked: spot, vehicleStartedAt: t0))
    }

    // MARK: - The engine

    @Test("A ride that starts across town proposes nothing and stays parked")
    func rideAcrossTownStaysParked() async {
        let engine = await parkedEngine(location: spot)

        let effects = await ride(engine, startNorth: 4_000)

        #expect(!proposes(effects))
        #expect(await engine.state == .parked)
        #expect(effects.contains(.stopLocationCapture))
    }

    @Test("The same ride from the spot is the user's departure")
    func rideFromSpotDeparts() async {
        let engine = await parkedEngine(location: spot)

        #expect(proposes(await ride(engine, startNorth: 100)))
    }

    @Test("A parking saved without a location keeps today's behaviour")
    func noLocationKeepsBehaviour() async {
        let engine = await parkedEngine(location: nil)

        #expect(proposes(await ride(engine, startNorth: 4_000)))
    }

    @Test("More vehicle evidence from the same ride opens nothing")
    func sameRideOpensNothing() async {
        let engine = await parkedEngine(location: spot)
        _ = await ride(engine, startNorth: 4_000)

        let effects = await engine.handle(.vehicleEnter(at: at(900)))

        #expect(!effects.contains(.startBoundedLocationCapture))
        #expect(await engine.state == .parked)
    }

    @Test("After the ride ends the user's own departure is judged afresh")
    func ownDepartureAfterRide() async {
        let engine = await parkedEngine(location: spot)
        _ = await ride(engine, startNorth: 4_000)
        _ = await engine.handle(.vehicleExit(at: at(1_300)))
        _ = await engine.handle(.walkingEnter(at: at(1_301)))

        #expect(proposes(await ride(engine, startNorth: 50, from: 5_000)))
    }
}
