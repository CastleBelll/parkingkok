import Foundation
import Testing
@testable import ParkingKok

@Suite("Diagnostics export")
struct DiagnosticsReportTests {
    private func report(checkpoint: DetectionCheckpoint?) -> DiagnosticsReport {
        var snapshot = RehydrationSnapshot()
        snapshot.currentCheckpoint = checkpoint
        return DiagnosticsReport(
            snapshot: snapshot,
            now: TestTime.offset(0),
            locationAuthorization: .always,
            motionAuthorization: .authorized,
            isMotionHistoryAvailable: true,
            isMonitoringSignificantChanges: true,
            isSmartDetectionEnabled: true,
            storeSetupFailure: nil,
            traceSummary: .empty
        )
    }

    /// The report is copied off the device, so a coordinate reaching it would walk
    /// parking locations straight past docs/00_CORE_RULES.md Privacy. `DetectionCheckpoint`
    /// is Codable and carries `lastReliableLocation`, so this has to be checked against
    /// the encoded bytes rather than against the field list.
    @Test("No coordinate survives into the encoded report, even when the checkpoint has one")
    func encodedReportCarriesNoCoordinate() throws {
        // Arrange — distinctive values that would be unmistakable in the output.
        let located = DetectionCheckpoint(
            state: .idle,
            stateEnteredAt: TestTime.offset(0),
            lastReliableLocation: LastReliableLocation(
                latitude: 37.123_456_7,
                longitude: 127.987_654_3,
                horizontalAccuracy: 12,
                capturedAt: TestTime.offset(-60)
            ),
            revision: 7
        )

        // Act
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let data = try encoder.encode(report(checkpoint: located))
        let json = try #require(String(data: data, encoding: .utf8))

        // Assert
        #expect(!json.contains("37.123"))
        #expect(!json.contains("127.987"))
        #expect(!json.lowercased().contains("latitude"))
        #expect(!json.lowercased().contains("longitude"))
        #expect(!json.lowercased().contains("lastreliablelocation"))
    }

    @Test("The reliable fix is reported as presence and quality, never as a place")
    func reliableLocationIsProjected() {
        // Arrange
        let located = DetectionCheckpoint(
            state: .idle,
            stateEnteredAt: TestTime.offset(0),
            lastReliableLocation: LastReliableLocation(
                latitude: 37.5,
                longitude: 127.5,
                horizontalAccuracy: 12,
                capturedAt: TestTime.offset(-60)
            )
        )

        // Act
        let withFix = report(checkpoint: located)
        let withoutFix = report(checkpoint: DetectionCheckpoint.initial(at: TestTime.offset(0)))

        // Assert
        #expect(withFix.hasReliableLocation)
        #expect(withFix.reliableLocationAccuracy == 12)
        #expect(withFix.reliableLocationCapturedAt == TestTime.offset(-60))
        #expect(!withoutFix.hasReliableLocation)
        #expect(withoutFix.reliableLocationAccuracy == nil)
    }

    @Test("The report carries the counters that only existed in memory")
    func carriesInMemoryEvidence() {
        // Arrange
        var snapshot = RehydrationSnapshot()
        snapshot.motionSamples = [
            MotionSample(timestamp: TestTime.offset(-30), automotive: true, stationary: true, confidence: .medium),
            MotionSample(timestamp: TestTime.offset(-10), walking: true, confidence: .high)
        ]
        snapshot.significantChangeCount = 3
        snapshot.staleLocationDropCount = 2
        snapshot.lastStaleLocationAge = 12000
        snapshot.traceReplayDropCount = 4
        snapshot.motionFailure = nil

        // Act
        let report = DiagnosticsReport(
            snapshot: snapshot,
            now: TestTime.offset(0),
            locationAuthorization: .whenInUse,
            motionAuthorization: .authorized,
            isMotionHistoryAvailable: true,
            isMonitoringSignificantChanges: false,
            isSmartDetectionEnabled: true,
            storeSetupFailure: nil,
            traceSummary: .empty
        )

        // Assert
        #expect(report.motionSampleCount == 2)
        #expect(report.motionSamples.count == 2)
        #expect(report.significantChangeCount == 3)
        #expect(report.staleLocationDropCount == 2)
        #expect(report.lastStaleLocationAge == 12000)
        // Recording refused four replayed fixes; the export is the only place that says so.
        #expect(report.traceReplayDropCount == 4)
        #expect(report.locationAuthorization == "whenInUse")
        #expect(report.motionAuthorization == "authorized")
        #expect(!report.isMonitoringSignificantChanges)
        // The automotive+stationary pair must survive the projection intact.
        #expect(report.motionSamples[0].automotive)
        #expect(report.motionSamples[0].stationary)
    }

    @Test("A damaged checkpoint is reported as damaged, not as a fresh install")
    func failedLoadIsVisible() {
        // Arrange
        var snapshot = RehydrationSnapshot()
        snapshot.checkpointLoad = .failed(.corrupt("NSCocoaErrorDomain(3840)"))

        // Act
        let report = DiagnosticsReport(
            snapshot: snapshot,
            now: TestTime.offset(0),
            locationAuthorization: .notDetermined,
            motionAuthorization: .notDetermined,
            isMotionHistoryAvailable: false,
            isMonitoringSignificantChanges: false,
            isSmartDetectionEnabled: false,
            storeSetupFailure: nil,
            traceSummary: .empty
        )

        // Assert
        #expect(report.checkpointLoad.contains("failed"))
        #expect(report.checkpointLoad.contains("3840"))
    }

    /// docs/05 §9: the four numbers exist so "is recording working?" can be answered from
    /// the diagnostics file alone, without retrieving a single trace.
    @Test("The trace summary is projected into the report")
    func traceSummaryIsProjected() {
        // Arrange
        var snapshot = RehydrationSnapshot()
        snapshot.traceFailure = "TraceStoreError(writeFailed)"
        let summary = TraceSummary(
            sessionCount: 7,
            eventCount: 412,
            droppedSessionCount: 3,
            unlabeledSessionCount: 2
        )

        // Act
        let report = DiagnosticsReport(
            snapshot: snapshot,
            now: TestTime.offset(0),
            locationAuthorization: .always,
            motionAuthorization: .authorized,
            isMotionHistoryAvailable: true,
            isMonitoringSignificantChanges: true,
            isSmartDetectionEnabled: true,
            storeSetupFailure: nil,
            traceSummary: summary
        )

        // Assert
        #expect(report.traceSessionCount == 7)
        #expect(report.traceEventCount == 412)
        #expect(report.traceDroppedSessionCount == 3)
        #expect(report.traceUnlabeledSessionCount == 2)
        #expect(report.traceFailure == "TraceStoreError(writeFailed)")
    }

    @Test("A written report round-trips off disk")
    func roundTripsThroughFile() throws {
        // Arrange
        let file = TemporaryCheckpointFile()
        let store = FileDiagnosticsReportStore(fileURL: file.url)
        let original = report(checkpoint: DetectionCheckpoint.initial(at: TestTime.offset(0)))

        // Act
        try store.write(original)
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let decoded = try decoder.decode(DiagnosticsReport.self, from: Data(contentsOf: file.url))

        // Assert
        #expect(decoded == original)
        #expect(decoded.schemaVersion == DiagnosticsReport.schemaVersion)
    }
}
