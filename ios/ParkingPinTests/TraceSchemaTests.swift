import Foundation
import Testing
@testable import ParkingPin

/// docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9 fixed the on-disk shape, and an Android
/// implementation is being written against the same text. These tests assert the encoded
/// JSON itself rather than the Swift types, because the bytes are the contract.
@Suite("Trace schema")
struct TraceSchemaTests {
    private func encode(_ session: TraceSession) throws -> [String: Any] {
        let data = try JSONEncoder().encode(session)
        return try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    @Test("A session round-trips through JSON unchanged")
    func sessionRoundTrips() throws {
        // Arrange
        let original = TestTrace.session(
            label: TraceLabel(mode: .bus, parked: false, note: "환승 포함"),
            events: [
                .motion(.vehicleEnter, at: TestTime.offset(0), confidence: .high),
                .location(at: TestTime.offset(30), accuracy: 8, speed: 9.2, distanceFromPreviousM: 41),
                .qualityDegraded(at: TestTime.offset(60), from: .good, to: .poor),
                .motion(.stationaryEnter, at: TestTime.offset(90), confidence: .medium),
                .motion(.stationaryExit, at: TestTime.offset(120), confidence: .medium),
                .motion(.vehicleExit, at: TestTime.offset(150), confidence: .medium),
                .motion(.walkingEnter, at: TestTime.offset(155), confidence: .high)
            ]
        )

        // Act
        let data = try JSONEncoder().encode(original)
        let decoded = try JSONDecoder().decode(TraceSession.self, from: data)

        // Assert
        #expect(decoded == original)
        #expect(decoded.schemaVersion == TraceSession.schemaVersion)
    }

    @Test("The session object carries exactly the §9 keys")
    func sessionKeysMatchContract() throws {
        // Arrange / Act
        let object = try encode(TestTrace.session())

        // Assert
        #expect(Set(object.keys) == [
            "schemaVersion", "sessionId", "platform", "deviceModel",
            "osVersion", "appVersion", "startedAt", "endedAt", "label", "events"
        ])
        #expect(object["platform"] as? String == "ios")
        #expect(object["deviceModel"] as? String == "iPhone15,3")
    }

    @Test("Absolute time is epoch milliseconds, the unit both platforms write")
    func timeIsEpochMillis() throws {
        // Arrange
        let started = Date(timeIntervalSince1970: 1_789_530_905.483)
        let session = TestTrace.session(
            startedAt: started,
            endedAt: started,
            events: [.motion(.vehicleEnter, at: started, confidence: .high)]
        )

        // Act
        let object = try encode(session)
        let events = try #require(object["events"] as? [[String: Any]])

        // Assert
        #expect(object["startedAt"] as? Int64 == 1_789_530_905_483)
        #expect(events[0]["atMillis"] as? Int64 == 1_789_530_905_483)
    }

    @Test("Each event type carries only the keys its shape defines")
    func eventKeysAreShapeSpecific() throws {
        // Arrange
        let session = TestTrace.session(events: [
            .motion(.vehicleEnter, at: TestTime.offset(0), confidence: .high),
            .location(at: TestTime.offset(30), accuracy: 8, speed: 9.2, distanceFromPreviousM: 41),
            .qualityDegraded(at: TestTime.offset(60), from: .good, to: .poor)
        ])

        // Act
        let object = try encode(session)
        let events = try #require(object["events"] as? [[String: Any]])

        // Assert — nil optionals must be omitted, not encoded as null.
        #expect(Set(events[0].keys) == ["type", "atMillis", "confidence"])
        #expect(events[0]["type"] as? String == "vehicle_enter")
        #expect(Set(events[1].keys) == ["type", "atMillis", "accuracy", "speed", "distanceFromPreviousM"])
        #expect(Set(events[2].keys) == ["type", "atMillis", "fromBucket", "toBucket"])
        #expect(events[2]["type"] as? String == "location_quality_degraded")
    }

    /// The wire strings are what the Android worker is matching and what the fixture
    /// converter reads. A rename that only touched the Swift case names would be invisible
    /// to the compiler and fatal to parity.
    @Test("The event vocabulary is exactly §2's and §8's, in snake_case")
    func vocabularyIsStable() {
        #expect(Set(TraceEventType.allCases.map(\.rawValue)) == [
            "vehicle_enter", "vehicle_exit", "walking_enter",
            "stationary_enter", "stationary_exit",
            "location", "location_quality_degraded"
        ])
        #expect(Set(TraceMode.allCases.map(\.rawValue)) == [
            "car", "bus", "subway", "taxi", "walk", "still", "unknown"
        ])
    }

    /// The thresholds are literals by instruction: `ReliableLocationPolicy`'s 35 m is a
    /// detection threshold headed for remote tuning, and binding the buckets to it would
    /// retroactively change what an already-recorded trace means.
    @Test("Accuracy buckets are good ≤20m, fair ≤35m, poor beyond")
    func accuracyBuckets() {
        #expect(LocationAccuracyBucket(horizontalAccuracy: 0) == .good)
        #expect(LocationAccuracyBucket(horizontalAccuracy: 20) == .good)
        #expect(LocationAccuracyBucket(horizontalAccuracy: 20.1) == .fair)
        #expect(LocationAccuracyBucket(horizontalAccuracy: 35) == .fair)
        #expect(LocationAccuracyBucket(horizontalAccuracy: 35.1) == .poor)
        #expect(LocationAccuracyBucket(horizontalAccuracy: 1000) == .poor)
    }

    /// docs/05 §5 calls a negative accuracy invalid and the adapters already reject it.
    /// One reaching here is an adapter defect, and a bucket would swallow it.
    @Test("An invalid accuracy gets no bucket at all")
    func invalidAccuracyHasNoBucket() {
        #expect(LocationAccuracyBucket(horizontalAccuracy: -1) == nil)
    }

    @Test("A note alone is not a label — the converter needs mode or parked")
    func labelCompleteness() {
        #expect(!TraceLabel.unlabeled.isLabeled)
        #expect(!TraceLabel(mode: .unknown, parked: nil, note: "지하 3층").isLabeled)
        #expect(TraceLabel(mode: .car, parked: nil, note: nil).isLabeled)
        #expect(TraceLabel(mode: .unknown, parked: false, note: nil).isLabeled)
    }
}
