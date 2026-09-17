import Foundation
import Testing
@testable import ParkingKok

/// The September 2026 field set, replayed against docs/05 §9's session-quality rules.
///
/// Nine sessions came off `00008120-001661423EA3C01E` over roughly a day and a half, and
/// they are the entire reason §9 grew a viability rule, a gap measurement and a manual
/// split. Transcribed rather than read from disk, like `FieldTraceReplayTests`, so the
/// suite is hermetic — and reduced to what these rules actually read: event **types** and
/// **times**. Accuracy, speed and distance are deliberately absent; no rule under test
/// looks at them, and the recorded values are exercised by `FieldTraceReplayTests`.
@Suite("Field trace session quality (2026-09-16/17, iPhone15,3)")
struct FieldTraceSessionQualityTests {
    /// One recorded session, as the gaps between its events.
    struct FieldSession {
        let id: String
        let mode: String
        let eventCount: Int
        let gapsMillis: [Int64]
    }

    /// All nine, in recording order.
    static let sessions: [FieldSession] = [
        FieldSession(id: "1789537784682", mode: "walk", eventCount: 35, gapsMillis: [
            22757, 23817, 16307, 17586, 17899, 15664, 17903, 20934,
            17758, 15973, 20004, 15006, 15006, 15005, 15005, 15005,
            15006, 15005, 15005, 15005, 15006, 15005, 15005, 30824,
            24000, 24051, 21086, 21008, 15002, 15006, 15005, 15006,
            17969, 24282
        ]),
        FieldSession(id: "1789542420179", mode: "still", eventCount: 1, gapsMillis: []),
        FieldSession(id: "1789544225798", mode: "subway", eventCount: 93, gapsMillis: [
            48952, 21117, 24388, 23619, 19888, 22880, 23998, 24392,
            23607, 27007, 17372, 21326, 23984, 23991, 24169, 23837,
            18204, 24401, 23620, 24142, 27029, 15003, 15005, 15005,
            15006, 15005, 15006, 25443, 15002, 15006, 1_244_575, 0,
            1_731_191, 1_701_044, 0, 462_462, 0, 306_927, 300_006, 0,
            0, 303_410, 0, 1_011_671, 351_547, 416_322, 0, 69044,
            16950, 0, 18517, 23019, -127_530, 127_530, 43324, 18019,
            0, 35318, 0, 16558, 19084, 18504, 44365, 26207,
            33613, 54517, 0, 18987, 7641, 0, 13912, 0,
            20345, 0, 24365, 8998, 0, 17853, 14114, 0,
            17900, 7781, 0, 1170, 0, 18576, 10388, 0,
            5473, 0, 0, 458_875
        ]),
        FieldSession(id: "1789555714641", mode: "still", eventCount: 1, gapsMillis: []),
        FieldSession(id: "1789557652245", mode: "walk", eventCount: 3, gapsMillis: [
            296_672, 0
        ]),
        FieldSession(id: "1789559891818", mode: "unknown", eventCount: 1, gapsMillis: []),
        FieldSession(id: "1789562396414", mode: "unknown", eventCount: 1, gapsMillis: []),
        FieldSession(id: "1789597645097", mode: "unknown", eventCount: 14, gapsMillis: [
            1_553_681, 0, 699_850, 332_136, 0, 416_533, 363_864, 348_621,
            306_624, 350_253, 0, 339_931, 307_020
        ]),
        FieldSession(id: "1789599444747", mode: "unknown", eventCount: 1, gapsMillis: [])
    ]

    /// The `subway` session in full: 93 events over 2h39m, of which the actual ride was
    /// 18:23–19:08. Offsets are milliseconds from its first event.
    static let subwayEvents: [(type: String, offsetMillis: Int64)] = [
        ("stationary_enter", 0), ("location", 48952),
        ("location", 70069), ("location", 94457),
        ("location", 118_076), ("location", 137_964),
        ("location", 160_844), ("location", 184_842),
        ("location", 209_234), ("location", 232_841),
        ("location", 259_848), ("location", 277_220),
        ("location", 298_546), ("location", 322_530),
        ("location", 346_521), ("location", 370_690),
        ("location", 394_527), ("location", 412_731),
        ("location", 437_132), ("location", 460_752),
        ("location", 484_894), ("location", 511_923),
        ("location", 526_926), ("location", 541_931),
        ("location", 556_936), ("location", 571_942),
        ("location", 586_947), ("location", 601_953),
        ("location", 627_396), ("location", 642_398),
        ("location", 657_404), ("walking_enter", 1_901_979),
        ("stationary_exit", 1_901_979), ("stationary_enter", 3_633_170),
        ("walking_enter", 5_334_214), ("stationary_exit", 5_334_214),
        ("location", 5_796_676), ("location_quality_degraded", 5_796_676),
        ("location", 6_103_603), ("location", 6_403_609),
        ("location_quality_degraded", 6_403_609), ("vehicle_enter", 6_403_609),
        ("location", 6_707_019), ("location_quality_degraded", 6_707_019),
        ("location", 7_718_690), ("location", 8_070_237),
        ("location", 8_486_559), ("location_quality_degraded", 8_486_559),
        ("location", 8_555_603), ("location", 8_572_553),
        ("location_quality_degraded", 8_572_553), ("location", 8_591_070),
        ("location", 8_614_089), ("location", 8_486_559),
        ("location", 8_614_089), ("location", 8_657_413),
        ("location", 8_675_432), ("location_quality_degraded", 8_675_432),
        ("location", 8_710_750), ("location_quality_degraded", 8_710_750),
        ("location", 8_727_308), ("location", 8_746_392),
        ("location", 8_764_896), ("location", 8_809_261),
        ("location", 8_835_468), ("location", 8_869_081),
        ("location", 8_923_598), ("location_quality_degraded", 8_923_598),
        ("location", 8_942_585), ("location", 8_950_226),
        ("location_quality_degraded", 8_950_226), ("location", 8_964_138),
        ("location_quality_degraded", 8_964_138), ("location", 8_984_483),
        ("location_quality_degraded", 8_984_483), ("location", 9_008_848),
        ("location", 9_017_846), ("location_quality_degraded", 9_017_846),
        ("location", 9_035_699), ("location", 9_049_813),
        ("location_quality_degraded", 9_049_813), ("location", 9_067_713),
        ("location", 9_075_494), ("location_quality_degraded", 9_075_494),
        ("location", 9_076_664), ("location_quality_degraded", 9_076_664),
        ("location", 9_095_240), ("location", 9_105_628),
        ("location_quality_degraded", 9_105_628), ("location", 9_111_101),
        ("vehicle_exit", 9_111_101), ("walking_enter", 9_111_101),
        ("stationary_enter", 9_569_976)
    ]

    private func session(from field: FieldSession) -> TraceSession {
        // The gaps alone rebuild a timeline: the rules under test read nothing else.
        var offset: Int64 = 0
        var events: [TraceEvent] = [Self.event("stationary_enter", offsetMillis: 0)]
        for gap in field.gapsMillis {
            offset += gap
            events.append(Self.event("stationary_enter", offsetMillis: offset))
        }
        return TestTrace.session(
            startedAt: TestTime.offset(0),
            endedAt: TestTime.offset(Double(offset) / 1000),
            events: events,
            gapStats: TraceGapStats(events: events)
        )
    }

    private static func event(_ type: String, offsetMillis: Int64) -> TraceEvent {
        let at = TestTime.offset(Double(offsetMillis) / 1000)
        switch TraceEventType(rawValue: type) {
        case .location:
            return .location(at: at, accuracy: 10)
        case .locationQualityDegraded:
            return .qualityDegraded(at: at, from: .good, to: .poor)
        case let .some(motion):
            return .motion(motion, at: at, confidence: .medium)
        case nil:
            // A type outside §2's wire vocabulary would be a transcription error, and
            // silently substituting one would hide it.
            return .motion(.stationaryEnter, at: at, confidence: .low)
        }
    }

    private var subwaySession: TraceSession {
        let events = Self.subwayEvents.map { Self.event($0.type, offsetMillis: $0.offsetMillis) }
        return TestTrace.session(
            startedAt: TestTime.offset(0),
            endedAt: TestTime.offset(Double(Self.subwayEvents[Self.subwayEvents.count - 1].offsetMillis) / 1000),
            label: TraceLabel(mode: .subway, parked: false, note: nil),
            events: events,
            gapStats: TraceGapStats(events: events)
        )
    }

    // MARK: - Non-viable sessions

    /// §9's claim, checked against the set it was written from: "실측에서 세션 9개 중
    /// 5개가 단일 이벤트였다."
    @Test("Five of the nine recorded sessions hold a single event")
    func fiveOfNineAreSingleEvent() {
        // Arrange / Act
        let nonViable = Self.sessions.filter { !TraceSessionBoundaryPolicy.isViable(eventCount: $0.eventCount) }

        // Assert
        #expect(Self.sessions.count == 9)
        #expect(nonViable.count == 5)
        #expect(nonViable.allSatisfy { $0.eventCount == 1 })
    }

    @Test("Applying the viability rule leaves four sessions, holding every multi-event trip")
    func fourSessionsSurviveTheViabilityRule() {
        // Arrange / Act
        let viable = Self.sessions.filter { TraceSessionBoundaryPolicy.isViable(eventCount: $0.eventCount) }

        // Assert — the walk, the subway commute, the short walk, and the morning trip.
        #expect(viable.map(\.eventCount) == [35, 93, 3, 14])
        #expect(viable.map(\.mode) == ["walk", "subway", "walk", "unknown"])
        // 145 of the 150 recorded events are kept: the five discarded sessions held one
        // event each, so the rule drops 56% of the sessions and 3% of the evidence.
        #expect(Self.sessions.reduce(0) { $0 + $1.eventCount } == 150)
        #expect(viable.reduce(0) { $0 + $1.eventCount } == 145)
    }

    // MARK: - Gap measurement

    /// The numbers §9 quotes, produced by the shipping measurement rather than restated.
    @Test("The subway session measures a 28.9-minute maximum gap")
    func subwaySessionGapStats() throws {
        // Arrange / Act
        let stats = try #require(subwaySession.gapStats)

        // Assert — 1_731_191 ms is 28.85 minutes: 66 seconds short of the 30-minute
        // threshold, which is why an hour and three quarters of sitting at a desk ended up
        // inside a session labelled `subway`.
        #expect(stats.maxGapMillis == 1_731_191)
        #expect(stats.gapsOver10MinCount == 4)
        #expect(stats.gapsOver20MinCount == 3)
        #expect(stats.maxGapMillis < Int64(TraceSessionBoundaryPolicy.idleGap * 1000))
    }

    @Test("Every recorded session's gaps stayed inside the 30-minute boundary")
    func noRecordedGapReachedTheThreshold() {
        // Arrange
        let threshold = Int64(TraceSessionBoundaryPolicy.idleGap * 1000)

        // Act / Assert — by construction: a longer silence would have rotated the session.
        // It is worth stating, because it is what makes the max gap a *near miss* rather
        // than a boundary that fired.
        for field in Self.sessions {
            #expect(field.gapsMillis.allSatisfy { $0 < threshold })
        }
    }

    @Test("Only two of the four viable sessions carry a gap over twenty minutes")
    func gapDistributionAcrossTheViableSessions() {
        // Arrange
        let viable = Self.sessions
            .filter { TraceSessionBoundaryPolicy.isViable(eventCount: $0.eventCount) }
            .map { session(from: $0) }

        // Act
        let stats = viable.compactMap(\.gapStats)

        // Assert
        #expect(stats.count == 4)
        #expect(stats.map(\.maxGapMillis) == [30824, 1_731_191, 296_672, 1_553_681])
        #expect(stats.count { $0.gapsOver20MinCount > 0 } == 2)
        #expect(stats.count { $0.gapsOver10MinCount > 0 } == 2)
    }

    // MARK: - The split a person would make

    /// §9's worked example. The ride began at the `vehicle_enter` 18:23; everything before
    /// the `walking_enter` at 18:06 was an office wait. A threshold cannot find that
    /// boundary — the office silences ran 20.7 / 28.9 / 28.4 minutes while a genuine
    /// stretch of the ride ran 16.9 — so a person picks the event.
    @Test("Splitting the subway session at the walk to the station yields two usable halves")
    func subwaySessionSplitsAtTheWalkToTheStation() throws {
        // Arrange — index 34 is the `walking_enter` that ends the office wait.
        let parent = subwaySession
        #expect(parent.events[34].type == .walkingEnter)

        // Act
        let split = try TraceSessionSplit.split(parent, atEventIndex: 34)

        // Assert — the office half keeps the long silences; the travelling half keeps only
        // the 16.9-minute one, which is the gap any lower threshold would have cut a real
        // journey at.
        #expect(split.leading.events.count == 34)
        #expect(split.trailing.events.count == 59)
        #expect(split.leading.gapStats?.maxGapMillis == 1_731_191)
        #expect(split.leading.gapStats?.gapsOver20MinCount == 2)
        #expect(split.trailing.gapStats?.maxGapMillis == 1_011_671)
        #expect(split.trailing.gapStats?.gapsOver20MinCount == 0)
        #expect(split.trailing.gapStats?.gapsOver10MinCount == 1)
        // The parent counted three gaps over twenty minutes and the halves count two: the
        // 28.4-minute silence at the cut belonged to neither half once it became the
        // boundary between them. A gap is a property of one session, and after the split
        // there are two.
        #expect(parent.gapStats?.gapsOver20MinCount == 3)
        // Both halves are sessions §9 would keep.
        #expect(TraceSessionBoundaryPolicy.isViable(eventCount: split.leading.events.count))
        #expect(TraceSessionBoundaryPolicy.isViable(eventCount: split.trailing.events.count))
        // And the mixed label does not follow either of them.
        #expect(split.leading.label.mode == .unknown)
        #expect(split.trailing.label.mode == .unknown)
    }

    /// The three-event walk is the smallest viable session in the set, and §9's fragment
    /// rule means it cannot be split at all — there is no cut leaving two events on both
    /// sides.
    @Test("The three-event walk cannot be split anywhere")
    func theSmallestViableSessionCannotBeSplit() {
        // Arrange
        let walk = session(from: Self.sessions[4])
        #expect(walk.events.count == 3)

        // Act / Assert
        #expect(throws: TraceSplitError.self) { try TraceSessionSplit.split(walk, atEventIndex: 1) }
        #expect(throws: TraceSplitError.self) { try TraceSessionSplit.split(walk, atEventIndex: 2) }
    }
}
