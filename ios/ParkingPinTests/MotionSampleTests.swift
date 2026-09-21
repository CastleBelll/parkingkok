import CoreMotion
import Foundation
import Testing
@testable import ParkingPin

@Suite("Motion normalization")
struct MotionSampleTests {
    @Test("automotive and stationary survive together — a car at a red light is both")
    func keepsAutomotiveAndStationaryTogether() {
        // Arrange / Act
        let sample = MotionSample(
            timestamp: TestTime.offset(0),
            automotive: true,
            stationary: true,
            confidence: .medium
        )

        // Assert — neither flag cancels the other (docs/04 §5).
        #expect(sample.automotive)
        #expect(sample.stationary)
        #expect(sample.hasKnownActivity)
        #expect(sample.activityFlagsDescription == "automotive+stationary")
    }

    @Test("A sample with no flags is kept and reported as unknown, not dropped")
    func reportsUnknownActivity() {
        // Arrange / Act
        let sample = MotionSample(timestamp: TestTime.offset(0), confidence: .low)

        // Assert
        #expect(!sample.hasKnownActivity)
        #expect(sample.activityFlagsDescription == "unknown")
    }

    /// Swift Testing destructures only pairs, so wider cases travel as a value.
    struct FlagCase: Sendable {
        let automotive: Bool
        let walking: Bool
        let stationary: Bool
        let running: Bool
        let expected: String
    }

    @Test(
        "Flag descriptions stay in a stable order",
        arguments: [
            FlagCase(automotive: true, walking: false, stationary: false, running: false, expected: "automotive"),
            FlagCase(automotive: false, walking: true, stationary: false, running: false, expected: "walking"),
            FlagCase(automotive: false, walking: false, stationary: true, running: false, expected: "stationary"),
            FlagCase(automotive: false, walking: false, stationary: false, running: true, expected: "running"),
            FlagCase(
                automotive: true,
                walking: true,
                stationary: true,
                running: true,
                expected: "automotive+walking+stationary+running"
            )
        ]
    )
    func describesFlagsInStableOrder(_ testCase: FlagCase) {
        // Arrange / Act
        let sample = MotionSample(
            timestamp: TestTime.offset(0),
            automotive: testCase.automotive,
            walking: testCase.walking,
            stationary: testCase.stationary,
            running: testCase.running,
            confidence: .high
        )

        // Assert
        #expect(sample.activityFlagsDescription == testCase.expected)
    }

    struct ConfidenceCase: Sendable {
        let input: CMMotionActivityConfidence
        let expected: MotionConfidence
    }

    @Test(
        "Core Motion confidence maps onto the contract bucket",
        arguments: [
            ConfidenceCase(input: .low, expected: .low),
            ConfidenceCase(input: .medium, expected: .medium),
            ConfidenceCase(input: .high, expected: .high)
        ]
    )
    func mapsConfidence(_ testCase: ConfidenceCase) {
        // Arrange / Act / Assert
        #expect(MotionConfidence(testCase.input) == testCase.expected)
    }

    @Test("Confidence buckets order low < medium < high")
    func ordersConfidence() {
        // Arrange / Act / Assert
        #expect(MotionConfidence.low < MotionConfidence.medium)
        #expect(MotionConfidence.medium < MotionConfidence.high)
        #expect(MotionConfidence.allCases.max() == .high)
    }

    @Test("Samples round-trip through Codable so they can be checkpointed later")
    func roundTripsThroughCodable() throws {
        // Arrange
        let sample = MotionSample(
            timestamp: TestTime.offset(12),
            automotive: true,
            stationary: true,
            confidence: .high
        )

        // Act
        let data = try JSONEncoder().encode(sample)
        let decoded = try JSONDecoder().decode(MotionSample.self, from: data)

        // Assert
        #expect(decoded == sample)
    }
}

@Suite("Motion history window")
struct MotionHistoryWindowPolicyTests {
    @Test("With no checkpoint the window is the full 30-minute lookback")
    func usesFullLookbackWithoutCheckpoint() {
        // Arrange
        let now = TestTime.offset(0)

        // Act
        let window = MotionHistoryWindowPolicy.window(now: now, checkpointDate: nil)

        // Assert
        #expect(window.end == now)
        #expect(window.duration == MotionHistoryWindowPolicy.maximumLookback)
    }

    @Test("A recent checkpoint wins over the lookback floor")
    func prefersRecentCheckpoint() {
        // Arrange
        let now = TestTime.offset(0)
        let checkpointDate = now.addingTimeInterval(-300)

        // Act
        let window = MotionHistoryWindowPolicy.window(now: now, checkpointDate: checkpointDate)

        // Assert — max(checkpoint.time, now - 30m) per docs/04 §6.
        #expect(window.start == checkpointDate)
    }

    @Test("An old checkpoint is clamped to the 30-minute floor")
    func clampsOldCheckpoint() {
        // Arrange
        let now = TestTime.offset(0)
        let checkpointDate = now.addingTimeInterval(-6 * 60 * 60)

        // Act
        let window = MotionHistoryWindowPolicy.window(now: now, checkpointDate: checkpointDate)

        // Assert
        #expect(window.start == now.addingTimeInterval(-MotionHistoryWindowPolicy.maximumLookback))
    }

    @Test("A checkpoint timestamped in the future never produces an inverted range")
    func clampsFutureCheckpoint() {
        // Arrange — device clock moved backwards.
        let now = TestTime.offset(0)
        let checkpointDate = now.addingTimeInterval(3600)

        // Act
        let window = MotionHistoryWindowPolicy.window(now: now, checkpointDate: checkpointDate)

        // Assert
        #expect(window.start == now)
        #expect(window.start <= window.end)
    }

    @Test("A checkpoint older than Core Motion's retention is flagged")
    func flagsBeyondRetention() {
        // Arrange
        let now = TestTime.offset(0)
        let eightDaysAgo = now.addingTimeInterval(-8 * 24 * 60 * 60)
        let oneDayAgo = now.addingTimeInterval(-24 * 60 * 60)

        // Act / Assert
        #expect(MotionHistoryWindowPolicy.isBeyondMotionRetention(checkpointDate: eightDaysAgo, now: now))
        #expect(!MotionHistoryWindowPolicy.isBeyondMotionRetention(checkpointDate: oneDayAgo, now: now))
    }
}
