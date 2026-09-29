import Foundation
import Testing
@testable import ParkingPin

/// docs/05 §14 "Both platforms persist and reload their whole engine state": a process death
/// is not an event. What the engine had in memory when the process died is what the next
/// process reads back, so a replay interrupted after **any** event reaches exactly the outcome
/// trace the uninterrupted replay reaches — and the golden both runners assert.
///
/// The one OS fact an iOS relaunch adds is that the capture died with the process; the relaunch
/// reopens whatever capture the restored state wants, a stop-only candidate's resume window
/// included (§3a "The window lives exactly as long as its capture"), so no death point needs a
/// reference of its own.
///
/// Android twin: `ParkingDetectionRuntimeRestoreTest` `a process death after any event of any
/// fixture or draft changes nothing`.
@Suite("Restore equals uninterrupted")
struct ParityRestoreTests {
    @Test("A process death after any event of any fixture or draft changes nothing")
    func processDeathAfterEveryEventChangesNothing() async throws {
        try await Self.assertEveryDeathPointMatchesUninterrupted(relaunch: .immediately)
    }

    /// The relaunch is the next event's wake, so the process stays dead for the whole gap and
    /// §3a's windows that lapsed meanwhile are settled by the restore, not by that event.
    ///
    /// The one rule a gap can add is §14 "The capture did not survive" step 1: a drive with
    /// neither vehicle evidence nor a fix for `vehicleEvidenceTimeout` (or past the 2-hour
    /// ceiling) at the relaunch ends in `IDLE`. Where it fires, the replay must match the
    /// uninterrupted one up to the death and then show exactly that row on the relaunch wake;
    /// what follows is a different trip and is not compared. Everywhere else it must match.
    @Test("A process death that lasts until the next event changes nothing")
    func processDeathUntilTheNextEventChangesNothing() async throws {
        try await Self.assertEveryDeathPointMatchesUninterrupted(relaunch: .atNextEvent)
    }

    @Test("A process death inside long_stop_in_traffic's resume window still withdraws the candidate")
    func deathInsideTheResumeWindowKeepsTheWindow() async throws {
        // Arrange
        let input = try #require(try ParityFixtureLoader.loadAll().first { $0.name == "long_stop_in_traffic" })
        let uninterrupted = try await ParityFixtureRunner.run(input)
        let created = try #require(uninterrupted.trace.first { $0.effects.contains { $0.hasPrefix("create") } })

        // Act
        let interrupted = try await ParityFixtureRunner.run(input, processDeathAfter: created.event)

        // Assert — the relaunch reopens the capture, which carries the window, so the jam moving
        // on withdraws the candidate exactly as it does without the death.
        let relaunch = try #require(interrupted.relaunch)
        #expect(relaunch.stateBeforeDeath.engine?.candidateResume != nil)
        #expect(relaunch.stateAfterRestore.engine?.candidateResume != nil)
        #expect(interrupted.effects.contains(.startBoundedLocationCapture))
        #expect(uninterrupted.trace.contains { $0.effects.contains("withdraw") })
        #expect(interrupted.trace == uninterrupted.trace)
    }

    private static func assertEveryDeathPointMatchesUninterrupted(
        relaunch relaunchTime: ParityFixtureRunner.RelaunchTime
    ) async throws {
        // Arrange
        let inputs = try ParityFixtureLoader.loadReplayInputs()
        let golden = try ParityFixtureLoader.loadGolden().fixtures
        #expect(!inputs.isEmpty, "no fixture reached the test bundle")

        for (key, input) in inputs.sorted(by: { $0.key < $1.key }) {
            let uninterrupted = try await ParityFixtureRunner.run(input)
            #expect(uninterrupted.trace == golden[key], "\(key): the uninterrupted replay left the golden")

            for death in input.events.indices {
                // Act
                let interrupted = try await ParityFixtureRunner.run(
                    input,
                    processDeathAfter: death,
                    relaunch: relaunchTime
                )
                let relaunch = try #require(interrupted.relaunch)

                // Assert — one report per fixture: the first death that diverges says enough.
                if Self.droppedAStaleDrive(relaunch) {
                    if let divergence = Self.staleDropDivergence(of: interrupted, from: uninterrupted, relaunch: relaunch) {
                        Issue.record("\(key): process death after event \(death) — \(divergence)")
                        break
                    }
                    continue
                }
                let divergence = Self.divergence(
                    of: interrupted,
                    from: uninterrupted,
                    relaunch: relaunch,
                    readBackVerbatim: relaunchTime == .immediately || death == input.events.count - 1
                )
                if let divergence {
                    Issue.record("\(key): process death after event \(death) — \(divergence)")
                    break
                }
            }
        }
    }

    /// §14 step 1 fired: the dead engine held a drive the relaunch-time session timeout ends.
    private static func droppedAStaleDrive(_ relaunch: ParityRelaunch) -> Bool {
        let dead = relaunch.stateBeforeDeath
        guard dead.state == .drivingCandidate || dead.state == .driving,
              let drive = dead.engine?.driving
        else { return false }
        return DrivingSessionTimeoutPolicy.relaunchExpiryReason(forDrive: drive, now: relaunch.at) != nil
    }

    /// Where step 1 fired: the uninterrupted trace up to the death, then `IDLE` on the relaunch
    /// wake with no candidate.
    private static func staleDropDivergence(
        of interrupted: ParityFixtureOutcome,
        from reference: ParityFixtureOutcome,
        relaunch: ParityRelaunch
    ) -> String? {
        let before = reference.trace.filter { $0.event <= relaunch.afterEvent }
        guard Array(interrupted.trace.prefix(before.count)) == before else {
            return "the trace before the death differs from the uninterrupted one"
        }
        guard relaunch.stateAfterRestore.state == .idle, relaunch.stateAfterRestore.engine?.driving == nil else {
            return "a stale drive was restored as \(relaunch.stateAfterRestore.state.rawValue)"
        }
        guard relaunch.outcomeEffects.isEmpty else {
            return "dropping a stale drive emitted \(relaunch.outcomeEffects)"
        }
        let wake = interrupted.trace[safe: before.count]
        guard let wake, wake.event == relaunch.afterEvent + 1, wake.state != nil,
              !wake.effects.contains(where: { $0.hasPrefix("create") })
        else {
            return "the relaunch wake is \(wake.map(String.init(describing:)) ?? "none"), not the drop to IDLE"
        }
        return nil
    }

    /// What a relaunch changed beyond the uninterrupted replay, or `nil` when it changed nothing.
    private static func divergence(
        of interrupted: ParityFixtureOutcome,
        from reference: ParityFixtureOutcome,
        relaunch: ParityRelaunch,
        readBackVerbatim: Bool
    ) -> String? {
        if readBackVerbatim {
            // Restored at the instant of death, the state is the dead one exactly (bar the
            // revision a write-back may have bumped).
            var expected = relaunch.stateBeforeDeath
            expected.revision = relaunch.stateAfterRestore.revision
            if relaunch.stateAfterRestore != expected {
                return "restored \(relaunch.stateAfterRestore), the dead engine held \(relaunch.stateBeforeDeath)"
            }
            if !relaunch.outcomeEffects.isEmpty {
                return "the restore itself emitted \(relaunch.outcomeEffects)"
            }
        }
        if interrupted.trace != reference.trace {
            let at = interrupted.trace.indices.first { interrupted.trace[$0] != reference.trace[safe: $0] }
                ?? interrupted.trace.count
            let wanted = reference.trace[safe: at].map(String.init(describing:)) ?? "none"
            let actual = interrupted.trace[safe: at].map(String.init(describing:)) ?? "none"
            return "trace entry \(at): uninterrupted \(wanted), restored \(actual)"
        }
        if interrupted.finalState != reference.finalState {
            return "final state \(interrupted.finalState.rawValue), uninterrupted \(reference.finalState.rawValue)"
        }
        return nil
    }
}

/// docs/05 §14 "The capture did not survive", step 1: a relaunch drops an open drive the session
/// timeout already ends — 2 h, or `vehicleEvidenceTimeout` with neither vehicle evidence nor a
/// fix — before any window is settled, so no latched promotion reopens a capture at wherever the
/// phone now is. Each test restores a schema-3 checkpoint (the engine state, not the legacy
/// path) and has an Android twin of the same name in `ParkingDetectionRuntimeTest`.
@Suite("A relaunch drops a stale drive")
struct StaleDriveRelaunchTests {
    private let t0 = TestTime.reference
    private let staleRelaunchGap: TimeInterval = 30 * 60
    private var sustain: TimeInterval { DrivingConfirmationPolicy.minimumVehicleDuration }

    private func at(_ seconds: TimeInterval) -> Date { t0.addingTimeInterval(seconds) }

    private func fix(at date: Date, north: Double, speed: Double? = nil) -> LocationFix {
        LocationFix(
            timestamp: date,
            latitude: 37.5 + north / 111_320,
            longitude: 127.0,
            horizontalAccuracy: 8,
            speed: speed
        )
    }

    /// `vehicle_enter` at 0 and a fix at +10 s: `DRIVING_CANDIDATE`, its capture open.
    private var enterVehicle: [DetectionEvent] {
        [.vehicleEnter(at: at(0)), .location(fix(at: at(10), north: 0))]
    }

    /// Two `vehicle_enter` a sustain apart and no moving fix: `DRIVING`, its capture open.
    private var confirmDriveWithoutMovement: [DetectionEvent] {
        [.vehicleEnter(at: at(0)), .vehicleEnter(at: at(sustain))]
    }

    private struct Relaunch {
        let before: DetectionState
        let stored: DetectionCheckpoint
        let engine: ParkingDetectionEngine
        let effects: [DetectionEffect]
    }

    private func relaunch(after events: [DetectionEvent], at relaunchAt: Date) async throws -> Relaunch {
        let original = ParkingDetectionEngine()
        var effects = await original.restore(nil, seedIfAbsent: false, now: t0)
        for event in events {
            effects += await original.handle(event)
        }
        let stored = try #require(effects.lazy.compactMap { effect -> DetectionCheckpoint? in
            if case let .persistCheckpoint(checkpoint) = effect { return checkpoint }
            return nil
        }.last)
        let engine = ParkingDetectionEngine()
        let restored = await engine.restore(stored, now: relaunchAt)
        return Relaunch(before: await original.state, stored: stored, engine: engine, effects: restored)
    }

    private func createdCandidate(_ effects: [DetectionEffect]) -> Bool {
        effects.contains { if case .createCandidate = $0 { return true } else { return false } }
    }

    @Test("A stale driving candidate restored after a relaunch reopens no capture and ends in IDLE")
    func staleDrivingCandidateEndsInIdle() async throws {
        // Arrange / Act — relaunched 30 min later, far past 600 s of vehicle silence.
        let reset = try await relaunch(after: enterVehicle, at: at(staleRelaunchGap))

        // Assert
        #expect(reset.before == .drivingCandidate)
        #expect(reset.stored.engine?.driving != nil, "a schema-3 checkpoint, not the legacy path")
        #expect(await reset.engine.state == .idle)
        #expect(await reset.engine.snapshot().driving == nil)
        #expect(!createdCandidate(reset.effects))
        #expect(!reset.effects.contains(.startBoundedLocationCapture))
        #expect(reset.effects.contains(.sessionEnded(reason: .vehicleEvidenceExpired, at: at(staleRelaunchGap))))
    }

    @Test("A stale drive restored after a relaunch reopens no capture and ends in IDLE")
    func staleDriveEndsInIdle() async throws {
        // Arrange / Act — a confirmed drive with no moving sample, which `movementIdleWindow`
        // correctly declines to end, relaunched 30 min on.
        let reset = try await relaunch(after: confirmDriveWithoutMovement, at: at(staleRelaunchGap))

        // Assert
        #expect(reset.before == .driving)
        #expect(reset.stored.engine?.driving != nil, "a schema-3 checkpoint, not the legacy path")
        #expect(await reset.engine.state == .idle)
        #expect(await reset.engine.snapshot().transitionDrive == nil, "no parking transition is opened")
        #expect(!createdCandidate(reset.effects))
        #expect(!reset.effects.contains(.startBoundedLocationCapture))
    }

    @Test("A drive restored inside its evidence window still reopens its capture")
    func freshDriveReopensItsCapture() async throws {
        // Arrange / Act — the control: the same drive, relaunched 60 s after its last evidence.
        let reset = try await relaunch(after: confirmDriveWithoutMovement, at: at(sustain + 60))

        // Assert
        #expect(await reset.engine.state == .driving)
        #expect(await reset.engine.snapshot().driving != nil)
        #expect(reset.effects.contains(.startBoundedLocationCapture))
    }

    @Test("A reboot mid-drive whose fixes kept arriving keeps the drive")
    func driveWhoseFixesKeptArrivingIsKept() async throws {
        // Arrange — one `vehicle_enter` and a fix every 30 s for 12 minutes; the relaunch lands
        // 60 s after the last fix, 13 min after the only vehicle evidence.
        let lastFix: TimeInterval = 12 * 60
        let fixes: [DetectionEvent] = stride(from: 30.0, through: lastFix, by: 30).map {
            .location(fix(at: at($0), north: $0 * 12, speed: 12))
        }

        // Act
        let reset = try await relaunch(after: [.vehicleEnter(at: at(0))] + fixes, at: at(lastFix + 60))

        // Assert
        #expect(reset.before == .driving)
        #expect(await reset.engine.state == .driving)
        #expect(await reset.engine.snapshot().driving != nil)
        #expect(!createdCandidate(reset.effects))
        #expect(reset.effects.contains(.startBoundedLocationCapture))
    }
}

private extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
