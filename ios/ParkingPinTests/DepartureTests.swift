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

    @Test("Reconnecting to the car opens a departure, and ends nothing yet")
    func carLinkOpensDeparture() async throws {
        // §11b. The mirror of §3a's disconnect row: the phone rejoining the car is the
        // strongest departure signal there is, and it used to do nothing at all here.
        let engine = await parkedEngine()

        let gotInAt = at(3600)
        let effects = await engine.handle(.carLinkConnected(at: gotInAt, kind: .bluetoothAudio))

        #expect(await engine.state == .departureCandidate)
        // Opening is not ending: §11a's rule is that only §7's guard closes a record.
        #expect(endedAt(effects) == nil, "a connect is not yet a drive")
    }

    @Test("Driving away after reconnecting ends the parking at the moment of the connect")
    func carLinkDepartureEndsAtTheConnect() async throws {
        let engine = await parkedEngine()

        let gotInAt = at(3600)
        var effects = await engine.handle(.carLinkConnected(at: gotInAt, kind: .bluetoothAudio))
        for step in 1 ... 12 {
            effects += await engine.handle(
                .location(fix(at: gotInAt.addingTimeInterval(Double(step) * 30), north: Double(step) * 300))
            )
        }

        #expect(await engine.state == .driving)
        let ended = try #require(endedAt(effects))
        // Better than the answer §11's bars gave: the record closes at the moment the phone
        // rejoined the car, not whenever 90 s and 500 m happened to be reached afterwards.
        #expect(ended == gotInAt)
    }

    @Test("Sitting in the car with the radio on returns to PARKED and ends nothing")
    func carLinkWithoutDrivingEndsNothing() async throws {
        // The false positive §3a's gating table was narrowed for, on the departure side:
        // a link with no drive behind it must cost the record nothing.
        let engine = await parkedEngine()

        let gotInAt = at(3600)
        var effects = await engine.handle(.carLinkConnected(at: gotInAt, kind: .bluetoothAudio))
        effects += await engine.handle(
            .timerTick(at: gotInAt.addingTimeInterval(DrivingConfirmationPolicy.drivingCandidateWindow + 60))
        )

        #expect(await engine.state == .parked)
        #expect(endedAt(effects) == nil)
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

    // MARK: - §11 rows twinned with Android's `ParkingDetectionEngineTest`

    /// Exactly on §11's two bars — 90 s of vehicle activity and 600 m — and short of §7's
    /// guard (120 s or 800 m), as Android's `departureCandidate()` fixture is built.
    private func departingEngine(departAt: Date) async -> ParkingDetectionEngine {
        let engine = await parkedEngine()
        _ = await engine.handle(.vehicleEnter(at: departAt))
        _ = await engine.handle(.location(fix(at: departAt.addingTimeInterval(50), north: 0)))
        _ = await engine.handle(.location(fix(
            at: departAt.addingTimeInterval(DrivingConfirmationPolicy.minimumVehicleDuration),
            north: 600
        )))
        return engine
    }

    /// docs/05 §11 "Departure rows are edges" (2026-09-27). Android twin: `a departure is
    /// confirmed on a later event than the one that opened it`.
    @Test("A departure is confirmed on a later event than the one that opened it")
    func departureConfirmsOnALaterEvent() async throws {
        // Arrange
        let engine = await parkedEngine()
        let departAt = at(3600)
        _ = await engine.handle(.vehicleEnter(at: departAt))
        _ = await engine.handle(.location(fix(at: departAt.addingTimeInterval(10), north: 0)))

        // Act — one fix clears §11's bars (130 s, 1000 m) and would also meet §7's guard.
        let opening = await engine.handle(.location(fix(at: departAt.addingTimeInterval(130), north: 1000)))
        let openedState = await engine.state
        let confirming = await engine.handle(.location(fix(at: departAt.addingTimeInterval(160), north: 1300)))

        // Assert — the event that opened the departure does not also confirm it; the next one
        // does, and the parking ends at the DEPARTURE_CANDIDATE entry.
        #expect(openedState == .departureCandidate)
        #expect(endedAt(opening) == nil)
        #expect(await engine.state == .driving)
        #expect(endedAt(confirming) == departAt.addingTimeInterval(130))
    }

    /// Android twin: `a departure whose guard is met on the lapse boundary is confirmed`.
    @Test("A departure whose guard is met on the lapse boundary is confirmed")
    func departureOnTheLapseBoundaryConfirms() async {
        // Arrange — the departure's only vehicle evidence is the vehicle_enter at departAt.
        let departAt = at(3600)
        let engine = await departingEngine(departAt: departAt)
        #expect(await engine.state == .departureCandidate, "the control")

        // Act — exactly `vehicleEvidenceMaxAge` later, with §7's guard satisfied.
        let effects = await engine.handle(.location(fix(
            at: departAt.addingTimeInterval(DrivingConfirmationPolicy.vehicleEvidenceMaxAge),
            north: 1500
        )))

        // Assert — recent evidence is inclusive (<=), so the lapse must be exclusive (>).
        #expect(await engine.state == .driving)
        #expect(endedAt(effects) == departAt.addingTimeInterval(DrivingConfirmationPolicy.minimumVehicleDuration))
    }

    /// Android twin: `the departure lapse is stamped at its deadline`.
    @Test("The departure lapse is stamped at its deadline")
    func departureLapseIsStampedAtItsDeadline() async {
        // Arrange
        let departAt = at(3600)
        let engine = await departingEngine(departAt: departAt)

        // Act — noticed long after the evidence went stale.
        let effects = await engine.handle(.timerTick(at: departAt.addingTimeInterval(900)))

        // Assert
        #expect(await engine.state == .parked)
        #expect(endedAt(effects) == nil)
        #expect(
            await engine.snapshot().checkpoint.stateEnteredAt
                == departAt.addingTimeInterval(DrivingConfirmationPolicy.drivingCandidateWindow)
        )
    }

    // MARK: - §11 "An event that confirms a departure is also read in DRIVING" (2026-09-28)

    private func createdCandidate(_ effects: [DetectionEffect]) -> ParkingCandidate? {
        effects.compactMap {
            if case let .createCandidate(candidate) = $0 { return candidate }
            return nil
        }.last
    }

    /// A hand-saved parking, then §11's two bars cleared at +700 s (100 s in the car, 600 m)
    /// and §7's guard still unmet there. Built as Android's `shortDepartureBeforeTheExit()`.
    private func shortDepartureBeforeTheExit() async -> ParkingDetectionEngine {
        let engine = ParkingDetectionEngine()
        _ = await engine.restore(nil, seedIfAbsent: false, now: t0)
        _ = await engine.handle(.userSavedParking(at: at(0)))
        _ = await engine.handle(.vehicleEnter(at: at(600)))
        _ = await engine.handle(.location(fix(at: at(610), north: 0)))
        _ = await engine.handle(.location(fix(at: at(700), north: 600)))
        return engine
    }

    /// The short hop into an underground garage: fixes stop on the ramp and the exit arrives
    /// before any later event has asked §7's guard, which elapsed time alone has since met.
    /// Android twin: `a vehicle_exit that confirms a departure also ends the drive`.
    @Test("A vehicle_exit that confirms a departure also ends the drive")
    func confirmingExitEndsTheDrive() async throws {
        // Arrange
        let engine = await shortDepartureBeforeTheExit()
        #expect(await engine.state == .departureCandidate, "the control: §11's bars, short of §7's guard")

        // Act — 140 s after the enter: §7's duration is met on the exit itself.
        let exiting = await engine.handle(.vehicleExit(at: at(740)))
        let exitState = await engine.state
        let exitEnteredAt = await engine.snapshot().checkpoint.stateEnteredAt
        let walking = await engine.handle(.walkingEnter(at: at(760)))

        // Assert — the old parking ends at the DEPARTURE_CANDIDATE entry, and the exit opens
        // the transition the walk confirms.
        #expect(endedAt(exiting) == at(700))
        #expect(exiting.contains(.sessionEnded(reason: .vehicleExit, at: at(740))))
        #expect(exitState == .parkingTransition)
        #expect(exitEnteredAt == at(740))
        #expect(await engine.state == .candidatePending)
        let candidate = try #require(createdCandidate(walking))
        #expect(candidate.reasonCodes.contains(.vehicleExitDetected))
        #expect(candidate.reasonCodes.contains(.walkingAfterVehicle))
    }

    /// Android twin: `a car link disconnect that confirms a departure opens the candidate outright`.
    @Test("A car link disconnect that confirms a departure opens the candidate outright")
    func confirmingDisconnectOpensTheCandidate() async throws {
        // Arrange
        let engine = await shortDepartureBeforeTheExit()

        // Act — §3a's link row, reached through the departure the disconnect confirmed.
        let effects = await engine.handle(.carLinkDisconnected(at: at(740), kind: .bluetoothAudio))

        // Assert — the end first, then the new parking, on the same event.
        let endIndex = try #require(effects.firstIndex { if case .endActiveParking = $0 { true } else { false } })
        let createIndex = try #require(effects.firstIndex { if case .createCandidate = $0 { true } else { false } })
        #expect(endedAt(effects) == at(700))
        #expect(endIndex < createIndex, "the parking ends before the next one is raised")
        #expect(await engine.state == .candidatePending)
        let candidate = try #require(createdCandidate(effects))
        #expect(candidate.reasonCodes.contains(.carProjectionDisconnected))
        #expect(candidate.reasonCodes.contains(.vehicleExitDetected))
    }

    /// Android twin: `a vehicle_exit that does not meet the guard still returns to PARKED`.
    @Test("A vehicle_exit that does not meet the guard still returns to PARKED")
    func unconfirmingExitReturnsToParked() async {
        // Arrange
        let engine = await shortDepartureBeforeTheExit()

        // Act — 110 s after the enter, 600 m: neither §7 bar.
        let effects = await engine.handle(.vehicleExit(at: at(710)))

        // Assert
        #expect(await engine.state == .parked)
        #expect(endedAt(effects) == nil)
    }

    /// `walking_enter` has no DRIVING row (§3a), so a walk that confirms a departure confirms
    /// it and nothing else. Android twin requested: `a walk that confirms a departure only
    /// confirms it`.
    @Test("A walk that confirms a departure only confirms it")
    func confirmingWalkOnlyConfirms() async {
        // Arrange
        let engine = await shortDepartureBeforeTheExit()

        // Act
        let effects = await engine.handle(.walkingEnter(at: at(740)))

        // Assert
        #expect(endedAt(effects) == at(700))
        #expect(await engine.state == .driving)
        #expect(createdCandidate(effects) == nil)
    }

    // MARK: - §11b the link edge is vehicle evidence for the departure's lapse (2026-09-28)

    /// Android twin: `a link connect after an earlier vehicle_enter holds the departure from
    /// the connect`.
    @Test("A link connect after an earlier vehicle_enter holds the departure from the connect")
    func connectAfterEarlierEnterHoldsFromTheConnect() async {
        // Arrange — sitting in the parked car, then the phone joins it.
        let engine = ParkingDetectionEngine()
        _ = await engine.restore(nil, seedIfAbsent: false, now: t0)
        _ = await engine.handle(.userSavedParking(at: at(0)))
        _ = await engine.handle(.vehicleEnter(at: at(600)))

        // Act — the connect lands after the enter's own window (600 + 300 s) has passed.
        _ = await engine.handle(.carLinkConnected(at: at(950), kind: .bluetoothAudio))
        let connected = await engine.state
        _ = await engine.handle(.timerTick(at: at(1200)))
        let held = await engine.state
        _ = await engine.handle(.timerTick(at: at(1300)))

        // Assert
        #expect(connected == .departureCandidate)
        #expect(held == .departureCandidate)
        #expect(await engine.state == .parked)
        #expect(
            await engine.snapshot().checkpoint.stateEnteredAt
                == at(950).addingTimeInterval(DrivingConfirmationPolicy.drivingCandidateWindow)
        )
    }

    /// Android twin: `a link connect inside DEPARTURE_CANDIDATE postpones the lapse`.
    @Test("A link connect inside DEPARTURE_CANDIDATE postpones the lapse")
    func connectInsideDeparturePostponesTheLapse() async {
        // Arrange — the departure's only vehicle evidence is the enter at departAt: it lapses
        // after departAt + 300 s unless something refreshes it.
        let departAt = at(3600)
        let lateTick = DetectionEvent.timerTick(at: departAt.addingTimeInterval(350))
        let control = await departingEngine(departAt: departAt)
        _ = await control.handle(lateTick)
        let engine = await departingEngine(departAt: departAt)

        // Act
        _ = await engine.handle(.carLinkConnected(at: departAt.addingTimeInterval(100), kind: .bluetoothAudio))
        let connected = await engine.state
        let effects = await engine.handle(lateTick)

        // Assert — without the connect the departure lapsed; with it, the evidence is still
        // recent, so the tick meets §7's guard instead.
        #expect(await control.state == .parked)
        #expect(connected == .departureCandidate)
        #expect(await engine.state == .driving)
        #expect(endedAt(effects) == departAt.addingTimeInterval(DrivingConfirmationPolicy.minimumVehicleDuration))
    }

    /// The disconnect half of §11b's sentence. Android twin requested: `a link disconnect
    /// inside DEPARTURE_CANDIDATE postpones the lapse`.
    @Test("A link disconnect inside DEPARTURE_CANDIDATE postpones the lapse")
    func disconnectInsideDeparturePostponesTheLapse() async {
        // Arrange — the disconnect at +100 s leaves §7's guard unmet (100 s, 600 m).
        let departAt = at(3600)
        let lateTick = DetectionEvent.timerTick(at: departAt.addingTimeInterval(350))
        let control = await departingEngine(departAt: departAt)
        let controlEffects = await control.handle(lateTick)
        let engine = await departingEngine(departAt: departAt)

        // Act
        _ = await engine.handle(.carLinkDisconnected(at: departAt.addingTimeInterval(100), kind: .bluetoothAudio))
        let disconnected = await engine.state
        let effects = await engine.handle(lateTick)

        // Assert — the disconnect's evidence is 250 s old at the tick: recent, so the tick
        // meets §7's guard where the control lapsed. (With the link gone, the same tick then
        // finds `movementIdleWindow` long closed; where that leads is §3a's business.)
        #expect(await control.state == .parked)
        #expect(endedAt(controlEffects) == nil)
        #expect(disconnected == .departureCandidate)
        #expect(effects.contains(.drivingConfirmed(at: departAt.addingTimeInterval(350))))
        #expect(endedAt(effects) == departAt.addingTimeInterval(DrivingConfirmationPolicy.minimumVehicleDuration))
    }

    /// §11: 500 m is measured per get-in. Android twin: `getting back out of a parked car
    /// drops the departure's evidence`.
    @Test("Getting back out of a parked car drops the departure's evidence")
    func gettingBackOutDropsTheEvidence() async {
        // Arrange
        let engine = await parkedEngine()
        let firstIn = at(3600)

        // Act — in, 300 m, out; then in again and another 300 m.
        _ = await engine.handle(.vehicleEnter(at: firstIn))
        _ = await engine.handle(.location(fix(at: firstIn.addingTimeInterval(10), north: 0)))
        _ = await engine.handle(.location(fix(at: firstIn.addingTimeInterval(40), north: 300)))
        _ = await engine.handle(.vehicleExit(at: firstIn.addingTimeInterval(50)))
        let afterExit = await engine.snapshot().driving
        let secondIn = firstIn.addingTimeInterval(600)
        _ = await engine.handle(.vehicleEnter(at: secondIn))
        _ = await engine.handle(.location(fix(at: secondIn.addingTimeInterval(10), north: 300)))
        _ = await engine.handle(.location(fix(at: secondIn.addingTimeInterval(100), north: 600)))

        // Assert — the second get-in has to earn §11's 500 m on its own.
        #expect(afterExit == nil)
        #expect(await engine.state == .parked)
    }

    // MARK: - §14: a process death inside DEPARTURE_CANDIDATE

    /// docs/05 §14 "A restored DEPARTURE_CANDIDATE is rebuilt, not dropped" (2026-09-28).
    /// Android keeps the whole engine state across a process death, so the departure it was
    /// watching can still end the parking; iOS used to drop it on the first tick after the
    /// relaunch. Android twin: `ParkingDetectionRuntimeTest` `a departure survives a process
    /// death and can still end the parking`.
    @Test("A departure restored after a process death can still end the parking")
    func restoredDepartureCanStillEndTheParking() async throws {
        // Arrange — the process died after §11's bars were cleared at `departAt`.
        let departAt = at(3600)
        let engine = ParkingDetectionEngine()
        let checkpoint = DetectionCheckpoint(
            state: .departureCandidate,
            stateEnteredAt: departAt,
            lastAutomotiveAt: departAt
        )

        // Act — relaunch, then keep driving: 900 m in 60 s with vehicle evidence fresh.
        let restoreEffects = await engine.restore(checkpoint, now: departAt.addingTimeInterval(10))
        let restoredState = await engine.state
        var effects: [DetectionEffect] = []
        effects += await engine.handle(.vehicleEnter(at: departAt.addingTimeInterval(20)))
        for step in 0 ... 3 {
            effects += await engine.handle(
                .location(fix(at: departAt.addingTimeInterval(30 + Double(step) * 20), north: Double(step) * 300))
            )
        }

        // Assert — the capture is reopened, and the parking ends when the car pulled away.
        #expect(restoredState == .departureCandidate)
        #expect(restoreEffects.contains(.startBoundedLocationCapture))
        #expect(await engine.state == .driving)
        #expect(endedAt(effects) == departAt)
    }

    /// The same rule's other half: the rebuilt evidence lapses exactly as the live one would.
    @Test("A departure restored with stale evidence returns to PARKED and ends nothing")
    func restoredStaleDepartureEndsNothing() async {
        // Arrange
        let departAt = at(3600)
        let engine = ParkingDetectionEngine()
        let checkpoint = DetectionCheckpoint(
            state: .departureCandidate,
            stateEnteredAt: departAt,
            lastAutomotiveAt: departAt
        )
        let lapse = departAt.addingTimeInterval(DrivingConfirmationPolicy.drivingCandidateWindow)

        // Act
        let effects = await engine.restore(checkpoint, now: lapse.addingTimeInterval(1))

        // Assert — back to PARKED, stamped at the lapse, no capture opened, nothing ended.
        #expect(await engine.state == .parked)
        #expect(await engine.snapshot().checkpoint.stateEnteredAt == lapse)
        #expect(!effects.contains(.startBoundedLocationCapture))
        #expect(endedAt(effects) == nil)
    }
}
