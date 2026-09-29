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
    /// it and nothing else. Android twin: `ParkingDetectionEngineTest` `a walk that confirms a
    /// departure only confirms it`.
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

    // MARK: - §11 "An adapter-decided end never leaves the parking behind" (2026-09-28)

    // iOS has no `vehicle_exit` edge on a device: `BackgroundCoordinator` derives the exit
    // from a walk (`endDrivingSession(.walkingDetected)`) and bounds silence the same way
    // (`.vehicleEvidenceExpired`). These are the tests of the path the device actually takes;
    // the `.vehicleExit` tests above are the fixture path. Android twins name the
    // `ParkingDetectionEngineTest` whose `VehicleExit` the derived end stands for.

    private func stopsCapture(_ effects: [DetectionEffect]) -> Bool {
        effects.contains(.stopLocationCapture)
    }

    private func withoutCheckpoints(_ effects: [DetectionEffect]) -> [DetectionEffect] {
        effects.filter {
            if case .persistCheckpoint = $0 { return false }
            return true
        }
    }

    /// Android twin: `ParkingDetectionEngineTest` `fromParked` `VehicleExit` row (the get-in
    /// is dropped, the state stays).
    @Test("A derived exit after getting back in keeps the parking and drops the get-in")
    func derivedExitInParkedKeepsTheParking() async {
        // Arrange — PARKED with a get-in session open (+600 enter, +610 fix).
        let engine = ParkingDetectionEngine()
        _ = await engine.restore(nil, seedIfAbsent: false, now: t0)
        _ = await run(Array(shortDepartureEvents.prefix(3)), on: engine)

        // Act
        let effects = await engine.endDrivingSession(reason: .walkingDetected, now: at(650))

        // Assert
        #expect(await engine.state == .parked)
        #expect(await engine.snapshot().driving == nil)
        #expect(await engine.snapshot().isVehicleActive == false)
        #expect(stopsCapture(effects))
        #expect(endedAt(effects) == nil)
    }

    /// Android twin: `a vehicle_exit that confirms a departure also ends the drive`.
    @Test("A derived exit that confirms a departure also ends the drive")
    func derivedExitConfirmingADepartureEndsTheDrive() async throws {
        // Arrange
        let engine = await shortDepartureBeforeTheExit()

        // Act — the coordinator's order: the derived end, then the walk that derived it.
        let exiting = await engine.endDrivingSession(reason: .walkingDetected, now: at(740))
        let exitState = await engine.state
        let walking = await engine.handle(.walkingEnter(at: at(760)))

        // Assert — the fixture's outcome (`manual_save_then_short_departure.json`).
        #expect(endedAt(exiting) == at(700))
        #expect(exitState == .parkingTransition)
        #expect(await engine.state == .candidatePending)
        let candidate = try #require(createdCandidate(walking))
        #expect(candidate.confidenceBucket == .medium)
        #expect(candidate.reasonCodes.contains(.vehicleExitDetected))
        #expect(candidate.reasonCodes.contains(.walkingAfterVehicle))
    }

    /// Android twin: `a vehicle_exit that does not meet the guard still returns to PARKED`.
    @Test(
        "A derived exit that does not meet the guard returns to PARKED",
        arguments: [DrivingSessionEndReason.walkingDetected, .vehicleEvidenceExpired]
    )
    func unconfirmingDerivedExitReturnsToParked(reason: DrivingSessionEndReason) async {
        // Arrange
        let engine = await shortDepartureBeforeTheExit()

        // Act — 110 s after the enter, 600 m: neither §7 bar.
        let effects = await engine.endDrivingSession(reason: reason, now: at(710))

        // Assert
        #expect(await engine.state == .parked)
        #expect(await engine.snapshot().driving == nil)
        #expect(stopsCapture(effects))
        #expect(endedAt(effects) == nil)
    }

    // A lost capture is not an exit (docs/05 §11 "A lost capture decides nothing"): it only
    // stops the capture, as it does in PARKING_TRANSITION, and the next edge or tick decides
    // — which is all Android can do, where a lost capture never reaches the engine.

    /// Android twin: `ParkingDetectionRuntimeTest` `capture lost at +710 then exit at +740
    /// still ends the parking at +700 and raises the medium candidate`.
    @Test(
        "Capture lost at +710 then exit at +740 still ends the parking at +700 and raises the medium candidate",
        arguments: [DrivingSessionEndReason.authorizationLost, .captureFailed]
    )
    func captureLossThenExitStillEndsTheParking(reason: DrivingSessionEndReason) async throws {
        // Arrange
        let engine = await shortDepartureBeforeTheExit()

        // Act
        let lost = await engine.endDrivingSession(reason: reason, now: at(710))
        let lostState = await engine.state
        let lostSession = await engine.snapshot().driving
        let exiting = await engine.handle(.vehicleExit(at: at(740)))
        let walking = await engine.handle(.walkingEnter(at: at(760)))

        // Assert — the loss only stops the capture; the exit meets §7 by elapsed time.
        #expect(withoutCheckpoints(lost) == [.stopLocationCapture], "a stop, and the write that records it")
        #expect(lostState == .departureCandidate)
        #expect(lostSession != nil)
        #expect(endedAt(exiting) == at(700))
        #expect(await engine.state == .candidatePending)
        let candidate = try #require(createdCandidate(walking))
        #expect(candidate.confidenceBucket == .medium)
        #expect(candidate.reasonCodes.contains(.vehicleExitDetected))
        #expect(candidate.reasonCodes.contains(.walkingAfterVehicle))
    }

    /// Android twin: `ParkingDetectionRuntimeTest` `capture lost at +710 with no further edge
    /// lapses to PARKED stamped +900` — the same events, the next one a tick at +901 s.
    @Test(
        "Capture lost at +710 with no further edge lapses to PARKED stamped +900",
        arguments: [DrivingSessionEndReason.authorizationLost, .captureFailed]
    )
    func captureLossWithNoFurtherEdgeLapses(reason: DrivingSessionEndReason) async {
        // Arrange
        let engine = await shortDepartureBeforeTheExit()
        _ = await engine.endDrivingSession(reason: reason, now: at(710))

        // Act — the next thing delivered is a tick past the enter's lapse (+900 s).
        let effects = await engine.handle(.timerTick(at: at(901)))

        // Assert
        #expect(await engine.state == .parked)
        #expect(await engine.snapshot().checkpoint.stateEnteredAt == at(900))
        #expect(await engine.snapshot().driving == nil)
        #expect(endedAt(effects) == nil)
    }

    /// iOS only (Android's lost capture is not an engine event, so its get-in is untouched by
    /// construction): a lost capture keeps the get-in, and a later edge can still clear §11.
    @Test(
        "A capture lost after getting back in keeps the get-in",
        arguments: [DrivingSessionEndReason.authorizationLost, .captureFailed]
    )
    func captureLossInParkedKeepsTheGetIn(reason: DrivingSessionEndReason) async {
        // Arrange — PARKED with a get-in session open (+600 enter, +610 fix).
        let engine = ParkingDetectionEngine()
        _ = await engine.restore(nil, seedIfAbsent: false, now: t0)
        _ = await run(Array(shortDepartureEvents.prefix(3)), on: engine)

        // Act
        let lost = await engine.endDrivingSession(reason: reason, now: at(650))
        let keptSession = await engine.snapshot().driving
        let keptVehicle = await engine.snapshot().isVehicleActive
        _ = await engine.handle(.location(fix(at: at(700), north: 600)))

        // Assert
        #expect(withoutCheckpoints(lost) == [.stopLocationCapture], "a stop, and the write that records it")
        #expect(keptSession != nil)
        #expect(keptVehicle)
        #expect(await engine.state == .departureCandidate)
    }

    /// docs/05 §11 "A lost capture decides nothing": the session a lost capture leaves
    /// behind has no capture, and a drive it becomes carries that into its transition — so
    /// a stop-only candidate at the end of it opens no resume window (§3a "The window lives
    /// exactly as long as its capture"). Android twin: `ParkingDetectionRuntimeTest` `a
    /// departure that lost its capture opens no resume window after it confirms`, where the
    /// runtime closes the window because no capture is running.
    @Test(
        "A departure that lost its capture opens no resume window after it confirms",
        arguments: [DrivingSessionEndReason.authorizationLost, .captureFailed]
    )
    func captureLostDepartureOpensNoResumeWindow(reason: DrivingSessionEndReason) async throws {
        // Arrange — lost at +710; the tick at +740 meets §7's guard by elapsed time; the
        // +700 moving fix puts `movementIdle` at +880, and stillness at +890 confirms it.
        let engine = await shortDepartureBeforeTheExit()
        _ = await engine.endDrivingSession(reason: reason, now: at(710))
        let confirming = await engine.handle(.timerTick(at: at(740)))
        let confirmedState = await engine.state
        let wantedWhileDriving = await engine.snapshot().isLocationCaptureWanted
        let stopped = await engine.handle(.stationaryEnter(at: at(890)))
        let candidate = try #require(createdCandidate(stopped))
        let wantedAfterCandidate = await engine.snapshot().isLocationCaptureWanted

        // Act — vehicle evidence inside what would have been the window (+880 … +1180).
        let boarding = await engine.handle(.vehicleEnter(at: at(950)))

        // Assert — the parking ended at +700, and the new evidence is a new journey that
        // leaves the candidate answerable.
        #expect(endedAt(confirming) == at(700))
        #expect(confirmedState == .driving)
        #expect(!wantedWhileDriving)
        #expect(!wantedAfterCandidate)
        #expect(!boarding.contains(.withdrawCandidate(id: candidate.id)))
        #expect(await engine.state == .drivingCandidate)
    }

    /// docs/05 §11 "A lost capture decides nothing" / §14: a process death does not give a
    /// session its lost capture back. Android twin: `ParkingDetectionRuntimeTest` `a departure
    /// that lost its capture reopens none after a process death`.
    @Test(
        "A departure that lost its capture reopens none after a process death",
        arguments: [DrivingSessionEndReason.authorizationLost, .captureFailed]
    )
    func captureLostDepartureReopensNoneOnRestore(reason: DrivingSessionEndReason) async throws {
        // Arrange — died right after the loss at +710.
        let original = ParkingDetectionEngine()
        _ = await original.restore(nil, seedIfAbsent: false, now: t0)
        var effects = await run(shortDepartureEvents, on: original)
        effects += await original.endDrivingSession(reason: reason, now: at(710))
        let checkpoint = try lastPersisted(effects)
        let engine = ParkingDetectionEngine()

        // Act
        let restored = await engine.restore(checkpoint, now: at(720))
        let wanted = await engine.snapshot().isLocationCaptureWanted
        let exiting = await engine.handle(.vehicleExit(at: at(740)))

        // Assert — the departure is still judged exactly as without the death.
        #expect(await original.state == .departureCandidate)
        #expect(!restored.contains(.startBoundedLocationCapture))
        #expect(!wanted)
        #expect(endedAt(exiting) == at(700))
        #expect(await engine.state == .parkingTransition)
    }

    /// The same for `PARKED`'s get-in. Android twin: `ParkingDetectionRuntimeTest` `a get-in
    /// that lost its capture reopens none after a process death`.
    @Test(
        "A get-in that lost its capture reopens none after a process death",
        arguments: [DrivingSessionEndReason.authorizationLost, .captureFailed]
    )
    func captureLostGetInReopensNoneOnRestore(reason: DrivingSessionEndReason) async throws {
        // Arrange — PARKED with a get-in (+600 enter, +610 fix), lost at +650, died.
        let original = ParkingDetectionEngine()
        _ = await original.restore(nil, seedIfAbsent: false, now: t0)
        var effects = await run(Array(shortDepartureEvents.prefix(3)), on: original)
        effects += await original.endDrivingSession(reason: reason, now: at(650))
        let checkpoint = try lastPersisted(effects)
        let engine = ParkingDetectionEngine()

        // Act
        let restored = await engine.restore(checkpoint, now: at(660))
        let wanted = await engine.snapshot().isLocationCaptureWanted
        _ = await engine.handle(shortDepartureEvents[3])

        // Assert — the get-in is kept, without a capture, and can still open the departure.
        #expect(!restored.contains(.startBoundedLocationCapture))
        #expect(!wanted)
        #expect(await engine.state == .departureCandidate)
    }

    /// docs/05 §11 "A lost capture decides nothing" / §14: the loss outlives the confirmation
    /// too. The drive a lost-capture departure became is persisted with its evidence and the
    /// loss, so a relaunch in `DRIVING` reopens no capture and the stop-only candidate at its
    /// end opens no resume window — the outcome with no process death. Android twin:
    /// `ParkingDetectionRuntimeTest` `a departure that lost its capture reopens none after it
    /// confirms and the process dies`.
    @Test(
        "A departure that lost its capture reopens none after it confirms and the process dies",
        arguments: [DrivingSessionEndReason.authorizationLost, .captureFailed]
    )
    func captureLostDepartureReopensNoneAfterItConfirms(reason: DrivingSessionEndReason) async throws {
        // Arrange — lost at +710, confirmed into DRIVING by the tick at +740, then died.
        let original = ParkingDetectionEngine()
        _ = await original.restore(nil, seedIfAbsent: false, now: t0)
        var effects = await run(shortDepartureEvents, on: original)
        effects += await original.endDrivingSession(reason: reason, now: at(710))
        effects += await original.handle(.timerTick(at: at(740)))
        let checkpoint = try lastPersisted(effects)
        let engine = ParkingDetectionEngine()

        // Act
        let restored = await engine.restore(checkpoint, now: at(750))
        let wanted = await engine.snapshot().isLocationCaptureWanted
        let stopped = await engine.handle(.stationaryEnter(at: at(890)))
        let candidate = try #require(createdCandidate(stopped))
        let boarding = await engine.handle(.vehicleEnter(at: at(950)))

        // Assert — the same outcome as `captureLostDepartureOpensNoResumeWindow`.
        #expect(await original.state == .driving)
        #expect(checkpoint.state == .driving)
        #expect(!restored.contains(.startBoundedLocationCapture))
        #expect(!wanted)
        #expect(!boarding.contains(.withdrawCandidate(id: candidate.id)))
        #expect(await engine.state == .drivingCandidate)
    }

    /// Android twin (same name, required by docs/05 §3a "Turning Smart Detection off"):
    /// turning detection off decides nothing, even for a departure §7's guard would confirm.
    @Test(
        "The opt-out inside a departure returns to PARKED and ends nothing",
        arguments: [DrivingSessionEndReason.smartDetectionDisabled, .fieldTestStopped]
    )
    func optOutInsideADepartureEndsNothing(reason: DrivingSessionEndReason) async {
        // Arrange
        let engine = await shortDepartureBeforeTheExit()

        // Act — +740: the guard is met by elapsed time.
        let effects = await engine.endDrivingSession(reason: reason, now: at(740))

        // Assert
        #expect(await engine.state == .parked)
        #expect(stopsCapture(effects))
        #expect(endedAt(effects) == nil)
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

    /// The disconnect half of §11b's sentence. Android twin: `ParkingDetectionEngineTest`
    /// `a link disconnect inside DEPARTURE_CANDIDATE postpones the lapse`.
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

    // MARK: - §14: a process death inside PARKED or DEPARTURE_CANDIDATE (2026-09-28)

    // docs/05 §14 "A restored departure keeps its evidence". A test naming an Android twin
    // shares its sentence and its events with that `ParkingDetectionRuntimeTest`; the only
    // iOS-only step is `restore(_:now:)`, which Android does not have — its runtime reloads
    // the engine state for every batch — and which is taken no later than the next event.

    /// The checkpoint a process death leaves behind: the last one the engine asked to persist.
    private func lastPersisted(_ effects: [DetectionEffect]) throws -> DetectionCheckpoint {
        let persisted = effects.compactMap {
            if case let .persistCheckpoint(checkpoint) = $0 { return checkpoint }
            return nil
        }.last
        return try #require(persisted, "nothing was persisted")
    }

    /// Feeds `events` in order and returns every effect they caused.
    private func run(_ events: [DetectionEvent], on engine: ParkingDetectionEngine) async -> [DetectionEffect] {
        var effects: [DetectionEffect] = []
        for event in events {
            effects += await engine.handle(event)
        }
        return effects
    }

    /// `shortDepartureBeforeTheExit()`'s events: a hand-saved parking, then §11's bars cleared
    /// at +700 s (100 s in the car, 600 m) with §7's guard still unmet.
    private var shortDepartureEvents: [DetectionEvent] {
        [
            .userSavedParking(at: at(0)),
            .vehicleEnter(at: at(600)),
            .location(fix(at: at(610), north: 0)),
            .location(fix(at: at(700), north: 600))
        ]
    }

    /// A fresh engine that plays `events`, dies, and is restored from its last checkpoint at
    /// `relaunchAt`.
    private func relaunched(
        after events: [DetectionEvent],
        at relaunchAt: Date
    ) async throws -> (engine: ParkingDetectionEngine, before: DetectionState, restore: [DetectionEffect]) {
        let original = ParkingDetectionEngine()
        _ = await original.restore(nil, seedIfAbsent: false, now: t0)
        let checkpoint = try await lastPersisted(run(events, on: original))
        let before = await original.state
        let engine = ParkingDetectionEngine()
        let restore = await engine.restore(checkpoint, now: relaunchAt)
        return (engine, before, restore)
    }

    /// Android twin: `a departure restored after a process death can still end the parking`.
    @Test("A departure restored after a process death can still end the parking")
    func restoredDepartureCanStillEndTheParking() async throws {
        // Arrange — died in DEPARTURE_CANDIDATE, relaunched at +705.
        let (engine, before, restoreEffects) = try await relaunched(after: shortDepartureEvents, at: at(705))
        let restoredState = await engine.state

        // Act — the next fix meets §7's guard only with the evidence gathered before the death
        // (160 s from the enter, 1 300 m, three moving samples).
        let effects = await engine.handle(.location(fix(at: at(760), north: 1300)))

        // Assert — the capture is reopened, and the parking ends at the DEPARTURE_CANDIDATE entry.
        #expect(before == .departureCandidate)
        #expect(restoredState == .departureCandidate)
        #expect(restoreEffects.contains(.startBoundedLocationCapture))
        #expect(await engine.state == .driving)
        #expect(endedAt(effects) == at(700))
    }

    /// Android twin: `a short departure restored before its exit still becomes the next parking`.
    @Test("A short departure restored before its exit still becomes the next parking")
    func restoredShortDepartureBecomesTheNextParking() async throws {
        // Arrange — `manual_save_then_short_departure.json` with a process death between its
        // +700 fix and its +740 exit, and the same fixture played without one as the control.
        let tail: [DetectionEvent] = [.vehicleExit(at: at(740)), .walkingEnter(at: at(760))]
        let control = ParkingDetectionEngine()
        _ = await control.restore(nil, seedIfAbsent: false, now: t0)
        let controlEffects = await run(shortDepartureEvents + tail, on: control)
        let controlCandidate = try #require(createdCandidate(controlEffects))
        let (engine, _, _) = try await relaunched(after: shortDepartureEvents, at: at(705))

        // Act
        let exiting = await engine.handle(tail[0])
        let exitState = await engine.state
        let walking = await engine.handle(tail[1])

        // Assert — the old parking ends at +700, the exit opens the transition, and the walk
        // raises the same candidate the uninterrupted run raised.
        #expect(endedAt(exiting) == at(700))
        #expect(exitState == .parkingTransition)
        #expect(await engine.state == .candidatePending)
        let candidate = try #require(createdCandidate(walking))
        #expect(candidate.confidenceBucket == .medium, "the fixture's expected confidence")
        #expect(candidate.confidenceBucket == controlCandidate.confidenceBucket)
        #expect(candidate.reasonCodes == controlCandidate.reasonCodes)
    }

    /// The same restore, ended the way a device ends it: a relaunch replays motion history,
    /// and `BackgroundCoordinator` turns the walk in it into a derived exit. Android twin:
    /// `a short departure restored before its exit still becomes the next parking`.
    @Test("A short departure restored before its derived exit still becomes the next parking")
    func restoredShortDepartureBecomesTheNextParkingOnADerivedExit() async throws {
        // Arrange
        let (engine, _, _) = try await relaunched(after: shortDepartureEvents, at: at(705))
        #expect(await engine.snapshot().isVehicleActive, "the coordinator derives an exit only while this is set")

        // Act
        let exiting = await engine.endDrivingSession(reason: .walkingDetected, now: at(740))
        let exitState = await engine.state
        let walking = await engine.handle(.walkingEnter(at: at(760)))

        // Assert
        #expect(endedAt(exiting) == at(700))
        #expect(exitState == .parkingTransition)
        #expect(await engine.state == .candidatePending)
        let candidate = try #require(createdCandidate(walking))
        #expect(candidate.confidenceBucket == .medium, "the fixture's expected confidence")
    }

    /// The stale-restore rule `restoreDrivingSession` applies, for `PARKED`'s get-in. A get-in
    /// whose vehicle evidence is hours old reopens no GPS; the parking stays. Android twin:
    /// `ParkingDetectionRuntimeTest` `a get-in restored with stale evidence reopens no capture
    /// and keeps the parking`.
    @Test("A get-in restored with stale evidence reopens no capture and keeps the parking")
    func restoredStaleGetInKeepsTheParking() async throws {
        // Arrange — the get-in's only vehicle evidence is the +600 enter.
        let relaunchAt = at(600).addingTimeInterval(DrivingSessionTimeoutPolicy.vehicleEvidenceTimeout + 1)

        // Act
        let (engine, before, effects) = try await relaunched(
            after: Array(shortDepartureEvents.prefix(3)),
            at: relaunchAt
        )

        // Assert
        #expect(before == .parked)
        #expect(await engine.state == .parked)
        #expect(await engine.snapshot().driving == nil)
        #expect(await engine.snapshot().isVehicleActive == false)
        #expect(!effects.contains(.startBoundedLocationCapture))
        #expect(endedAt(effects) == nil)
        #expect(try lastPersisted(effects).departure == nil, "the dropped get-in is not restored again")
    }

    /// Android twin: `a departure restored with stale evidence returns to PARKED and ends
    /// nothing` — there, the relaunch's first event is a tick at +901.
    @Test("A departure restored with stale evidence returns to PARKED and ends nothing")
    func restoredStaleDepartureEndsNothing() async throws {
        // Arrange — the departure's only vehicle evidence is the +600 enter: it lapses at +900.
        let lapse = at(600).addingTimeInterval(DrivingConfirmationPolicy.drivingCandidateWindow)

        // Act
        let (engine, before, effects) = try await relaunched(after: shortDepartureEvents, at: at(901))

        // Assert — back to PARKED, stamped at the lapse, no capture opened, nothing ended.
        #expect(before == .departureCandidate)
        #expect(await engine.state == .parked)
        #expect(await engine.snapshot().checkpoint.stateEnteredAt == lapse)
        #expect(!effects.contains(.startBoundedLocationCapture))
        #expect(endedAt(effects) == nil)
    }

    /// docs/05 §11b / §14: a departure the link opened is dated from the connect — the link
    /// edge is vehicle evidence, and it survives the process death with the rest of the
    /// departure, rather than being rebuilt from the older `vehicle_enter`. Android twin:
    /// `a departure a link connect opened keeps its postponed lapse across a process death`.
    @Test("A departure a link connect opened keeps its postponed lapse across a process death")
    func linkOpenedDepartureKeepsItsLapseAcrossAProcessDeath() async throws {
        // Arrange — the connect at +950 lands after the enter's own window (600 + 300 s), so
        // the departure's lapse runs from the connect: +1250.
        let events: [DetectionEvent] = [
            .userSavedParking(at: at(0)),
            .vehicleEnter(at: at(600)),
            .carLinkConnected(at: at(950), kind: .bluetoothAudio)
        ]
        let (engine, before, _) = try await relaunched(after: events, at: at(1200))

        // Act
        _ = await engine.handle(.timerTick(at: at(1200)))
        let held = await engine.state
        let effects = await engine.handle(.timerTick(at: at(1300)))

        // Assert
        #expect(before == .departureCandidate)
        #expect(held == .departureCandidate)
        #expect(await engine.state == .parked)
        #expect(
            await engine.snapshot().checkpoint.stateEnteredAt
                == at(950).addingTimeInterval(DrivingConfirmationPolicy.drivingCandidateWindow)
        )
        #expect(endedAt(effects) == nil)
    }

    /// The other §11b edge: a connect *inside* the departure postpones its lapse, and that
    /// is persisted when it happens, not at the next state change (docs/05 §14). Android
    /// twin: `ParkingDetectionRuntimeTest` `a link connect inside a departure still postpones
    /// its lapse after a process death`.
    @Test("A link connect inside a departure still postpones its lapse after a process death")
    func linkConnectInsideDepartureSurvivesAProcessDeath() async throws {
        // Arrange — the connect at +710 (guard unmet: 110 s, 600 m) moves the lapse from +900
        // to +1010, and the process dies right after it.
        let events = shortDepartureEvents + [.carLinkConnected(at: at(710), kind: .bluetoothAudio)]
        let (engine, _, _) = try await relaunched(after: events, at: at(1000))
        let restoredState = await engine.state

        // Act — past the enter's lapse, inside the connect's.
        let effects = await engine.handle(.location(fix(at: at(1000), north: 1300)))

        // Assert
        #expect(restoredState == .departureCandidate)
        #expect(await engine.state == .driving)
        #expect(endedAt(effects) == at(700))
    }

    /// §14 covers `PARKED`'s get-in session too: it is what §11's bars measure. Android twin:
    /// `ParkingDetectionRuntimeTest` `a get-in restored before the departure bars can still
    /// open the departure`.
    @Test("A get-in restored before the departure bars can still open the departure")
    func restoredGetInCanStillOpenTheDeparture() async throws {
        // Arrange — died after the +610 fix, before the +700 fix clears 500 m.
        let (engine, before, restoreEffects) = try await relaunched(
            after: Array(shortDepartureEvents.prefix(3)),
            at: at(650)
        )

        // Act
        _ = await engine.handle(shortDepartureEvents[3])

        // Assert
        #expect(before == .parked)
        #expect(restoreEffects.contains(.startBoundedLocationCapture))
        #expect(await engine.state == .departureCandidate)
    }

    /// iOS only: a checkpoint written before schema 2 has no departure evidence, and nothing
    /// is invented in its place — the departure is not decided on a relaunch (§11: leaving a
    /// record open is recoverable, ending one wrongly is not).
    @Test("A departure checkpoint without its evidence returns to PARKED and ends nothing")
    func legacyDepartureCheckpointReturnsToParked() async {
        // Arrange
        let departAt = at(3600)
        let engine = ParkingDetectionEngine()
        let legacy = DetectionCheckpoint(
            state: .departureCandidate,
            stateEnteredAt: departAt,
            lastAutomotiveAt: departAt
        )

        // Act
        let effects = await engine.restore(legacy, now: departAt.addingTimeInterval(10))

        // Assert
        #expect(await engine.state == .parked)
        #expect(await engine.snapshot().driving == nil)
        #expect(!effects.contains(.startBoundedLocationCapture))
        #expect(endedAt(effects) == nil)
    }
}
