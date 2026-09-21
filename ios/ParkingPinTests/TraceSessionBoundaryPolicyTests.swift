import Foundation
import Testing
@testable import ParkingPin

/// Where one recorded trip ends and the next begins.
///
/// Case for case the same suite as Android's `TraceSessionBoundaryPolicyTest`: the boundary
/// is fixed by docs/05 §9 for both platforms, so the same event stream has to produce the
/// same sessions on each. Every case is decided from event timestamps alone — no clock is
/// read and nothing sleeps (docs/16_CODING_STANDARDS.md §8).
@Suite("Trace session boundary")
struct TraceSessionBoundaryPolicyTests {
    private func session(
        startedAt: Date = TestTime.offset(0),
        endedAt: Date = TestTime.offset(0),
        eventCount: Int = 1
    ) -> TraceSession {
        TraceSession(
            sessionId: UUID(),
            metadata: TestTrace.metadata,
            startedAt: startedAt,
            endedAt: endedAt,
            events: Array(
                repeating: TraceEvent.location(at: startedAt, accuracy: 10),
                count: eventCount
            )
        )
    }

    private func reason(
        _ open: TraceSession?,
        at date: Date
    ) -> TraceSessionBoundaryPolicy.RotationReason? {
        TraceSessionBoundaryPolicy.rotationReason(for: open, nextEventAt: date)
    }

    @Test("Nothing open is not a rotation")
    func nothingOpenIsNotARotation() {
        // Arrange — the first event ever recorded.

        // Act
        let rotation = reason(nil, at: TestTime.offset(0))

        // Assert — the caller opens a session; it did not close one.
        #expect(rotation == nil)
    }

    /// Significant-change wakes arrive every few hundred metres and Core Motion history is
    /// only re-read on a wake, so a sparse trace is the OS, not a new trip.
    @Test("A gap shorter than the idle gap keeps one session")
    func sparseDeliveryKeepsOneSession() {
        // Arrange
        let open = session(endedAt: TestTime.offset(0))

        // Act
        let rotation = reason(open, at: TestTime.offset(TraceSessionBoundaryPolicy.idleGap - 1))

        // Assert
        #expect(rotation == nil)
    }

    @Test("One second short of the idle gap is still the same trip")
    func justInsideTheIdleGapContinues() {
        // Arrange — 29 min 59 s of silence.
        let open = session(endedAt: TestTime.offset(0))

        // Act
        let rotation = reason(open, at: TestTime.offset(29 * 60 + 59))

        // Assert
        #expect(rotation == nil)
    }

    @Test("Silence for the idle gap ends the trip")
    func idleGapEndsTheTrip() {
        // Arrange — exactly 30 minutes.
        let open = session(endedAt: TestTime.offset(0))

        // Act
        let rotation = reason(open, at: TestTime.offset(30 * 60))

        // Assert
        #expect(rotation == .idleGap)
    }

    @Test("The idle gap is measured from the last event, not from the session start")
    func idleGapIsMeasuredFromTheLastEvent() {
        // Arrange — a two-hour drive that ended a minute ago is still the same trip.
        let open = session(startedAt: TestTime.offset(0), endedAt: TestTime.offset(2 * 60 * 60))

        // Act
        let rotation = reason(open, at: TestTime.offset(2 * 60 * 60 + 60))

        // Assert
        #expect(rotation == nil)
    }

    /// The case the rolling cap cannot handle on its own: one unbounded session cannot be
    /// partly evicted.
    @Test("A session that never falls silent is still capped by duration")
    func durationCapHolds() {
        // Arrange
        let cap = TraceSessionBoundaryPolicy.maximumDuration
        let open = session(startedAt: TestTime.offset(0), endedAt: TestTime.offset(cap - 1000))

        // Act
        let rotation = reason(open, at: TestTime.offset(cap))

        // Assert
        #expect(rotation == .maximumDuration)
    }

    @Test("A pathological event rate is capped by count")
    func eventCapHolds() {
        // Arrange — inside the duration bound, so only the count can fire.
        let open = session(endedAt: TestTime.offset(0), eventCount: TraceSessionBoundaryPolicy.maximumEvents)

        // Act
        let rotation = reason(open, at: TestTime.offset(1))

        // Assert
        #expect(rotation == .maximumEvents)
    }

    @Test("One event short of the count cap still appends")
    func justUnderTheEventCapContinues() {
        // Arrange
        let open = session(endedAt: TestTime.offset(0), eventCount: TraceSessionBoundaryPolicy.maximumEvents - 1)

        // Act
        let rotation = reason(open, at: TestTime.offset(1))

        // Assert
        #expect(rotation == nil)
    }

    /// An NTP correction mid-trip. The converter turns `atMillis` into relative seconds
    /// from the first event, so a negative offset is an unreplayable fixture.
    @Test("A wall-clock jump backwards splits rather than corrupting the recording")
    func clockJumpSplits() {
        // Arrange
        let open = session(startedAt: TestTime.offset(0), endedAt: TestTime.offset(60))

        // Act
        let rotation = reason(open, at: TestTime.offset(-60))

        // Assert
        #expect(rotation == .clockWentBackwards)
    }

    /// An event stamped a hair before the session opened is routine; OEM timestamps and
    /// the wall clock do not agree to the millisecond.
    @Test("Ordinary clock skew is not a clock jump")
    func clockSkewIsTolerated() {
        // Arrange
        let open = session(startedAt: TestTime.offset(0), endedAt: TestTime.offset(0))

        // Act
        let rotation = reason(open, at: TestTime.offset(-1))

        // Assert
        #expect(rotation == nil)
    }

    /// The constants are the contract, not an implementation detail: §9 fixes them for both
    /// platforms, and a drift here is a parity break no fixture would reveal.
    @Test("The §9 constants match the values Android uses")
    func constantsMatchTheContract() {
        // Arrange / Act / Assert
        #expect(TraceSessionBoundaryPolicy.idleGap == 30 * 60)
        #expect(TraceSessionBoundaryPolicy.maximumDuration == 4 * 60 * 60)
        #expect(TraceSessionBoundaryPolicy.maximumEvents == 1000)
        #expect(TraceSessionBoundaryPolicy.clockTolerance == 5)
    }
}
