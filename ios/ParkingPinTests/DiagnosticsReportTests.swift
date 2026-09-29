import Foundation
import Testing
@testable import ParkingPin

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
            monitoringStartedAt: nil,
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

    /// docs/05 §14: `DetectionCheckpoint.engine` is the whole engine state, and its drives —
    /// the open session, a transition's, a pending candidate's — hold anchor fixes the
    /// `lastReliableLocation` guard above never seeds. The checkpoint file is local-only and
    /// may hold them; the export may not — neither through the current checkpoint nor through
    /// the load result that restored it.
    @Test("No engine-state anchor survives into the encoded report")
    func encodedReportCarriesNoEngineStateAnchor() throws {
        // Arrange
        let departing = engineStateCheckpoint()
        var snapshot = RehydrationSnapshot()
        snapshot.checkpointLoad = .restored(departing)
        snapshot.currentCheckpoint = departing
        let exported = DiagnosticsReport(
            snapshot: snapshot,
            now: TestTime.offset(0),
            locationAuthorization: .always,
            motionAuthorization: .authorized,
            isMotionHistoryAvailable: true,
            isMonitoringSignificantChanges: true,
            monitoringStartedAt: nil,
            isSmartDetectionEnabled: true,
            storeSetupFailure: nil,
            traceSummary: .empty
        )

        // Act
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        let json = try #require(String(data: encoder.encode(exported), encoding: .utf8))

        // Assert
        #expect(departing.engine?.driving?.lastFix != nil, "the seed must actually hold anchors")
        #expect(departing.engine?.transition?.drive.lastFix != nil)
        #expect(departing.engine?.candidateDrive?.lastFix != nil)
        #expect(!json.contains("37.123"))
        #expect(!json.contains("127.987"))
        #expect(!json.lowercased().contains("latitude"))
        #expect(!json.lowercased().contains("longitude"))
        #expect(!json.lowercased().contains("anchor"))
    }

    /// The one checkpoint string that reaches a log (`BackgroundCoordinator.rehydrate` logs a
    /// failed load's `diagnosticDescription`) and the report: a corrupt engine state must not
    /// echo its coordinates through it.
    @Test("A corrupt engine-state checkpoint is reported without its coordinates")
    func corruptDepartureCheckpointLeaksNoCoordinate() throws {
        // Arrange — a real departure checkpoint whose first latitude is made a string.
        let file = TemporaryCheckpointFile()
        let store = FileDetectionCheckpointStore(fileURL: file.url)
        try store.save(engineStateCheckpoint())
        let written = try String(contentsOf: file.url, encoding: .utf8)
        let latitude = try #require(written.range(of: #""latitude":37\.123[0-9]*"#, options: .regularExpression))
        let value = written[latitude].dropFirst(#""latitude":"#.count)
        try written.replacingCharacters(in: latitude, with: "\"latitude\":\"\(value)\"")
            .write(to: file.url, atomically: true, encoding: .utf8)

        // Act
        let load = store.load()

        // Assert
        guard case let .failed(failure) = load else {
            Issue.record("expected a failed load, got \(load)")
            return
        }
        #expect(!failure.diagnosticDescription.contains("37.123"))
        #expect(!failure.diagnosticDescription.contains("127.987"))
    }

    /// A whole engine state whose every drive holds two anchor fixes at distinctive
    /// coordinates — more than any one real state holds at once, so no drive goes unchecked.
    private func engineStateCheckpoint() -> DetectionCheckpoint {
        var drive = DrivingEvidence(startedAt: TestTime.offset(-120), lastVehicleEvidenceAt: TestTime.offset(-120))
        // 60 m apart, so every anchor the evidence keeps starts with the same digits.
        for (offset, north) in [(-110.0, 0.0), (-20.0, 60.0)] {
            _ = drive.record(fix: LocationFix(
                timestamp: TestTime.offset(offset),
                latitude: 37.123_456_7 + north / 111_320,
                longitude: 127.987_654_3,
                horizontalAccuracy: 8,
                speed: 12
            ))
        }
        var engine = DetectionEngineRecord()
        engine.driving = drive
        engine.vehicleActiveSince = TestTime.offset(-120)
        engine.isVehicleActive = true
        engine.candidateDrive = drive
        engine.transition = ParkingTransition(
            enteredAt: TestTime.offset(-20),
            entryReason: .movementIdle,
            drive: drive,
            driveDurationAtEnd: 100,
            sessionStartedAt: TestTime.offset(-120),
            vehicleExitDetected: false,
            isCapturing: true
        )
        return DetectionCheckpoint(
            state: .departureCandidate,
            stateEnteredAt: TestTime.offset(-20),
            engine: engine,
            revision: 9
        )
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
            monitoringStartedAt: nil,
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
            monitoringStartedAt: nil,
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
            monitoringStartedAt: nil,
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

    /// Twice a silent period was diagnosed from elapsed wall-clock rather than from the
    /// device — once as a dead registration, once as a healthy one. Both readings were
    /// wrong. The file now carries the two numbers that settle it without guessing.
    @Test("Monitoring age and input silence are reported separately")
    func reportsMonitoringLiveness() {
        // Arrange — monitoring up for an hour, last input ten minutes ago.
        var snapshot = RehydrationSnapshot()
        snapshot.lastLocationAt = TestTime.offset(-600)
        snapshot.motionSamples = [MotionSample(timestamp: TestTime.offset(-900), walking: true, confidence: .high)]

        // Act
        let report = DiagnosticsReport(
            snapshot: snapshot,
            now: TestTime.offset(0),
            locationAuthorization: .always,
            motionAuthorization: .authorized,
            isMotionHistoryAvailable: true,
            isMonitoringSignificantChanges: true,
            monitoringStartedAt: TestTime.offset(-3600),
            isSmartDetectionEnabled: true,
            storeSetupFailure: nil,
            traceSummary: .empty
        )

        // Assert — the newest input wins, not the oldest.
        #expect(report.monitoringAge == 3600)
        #expect(report.timeSinceLastDetectionInput == 600)
    }

    @Test("Silence is absent rather than zero when nothing has arrived")
    func silenceIsAbsentWithoutInput() {
        // Arrange / Act
        let report = DiagnosticsReport(
            snapshot: RehydrationSnapshot(),
            now: TestTime.offset(0),
            locationAuthorization: .always,
            motionAuthorization: .authorized,
            isMotionHistoryAvailable: true,
            isMonitoringSignificantChanges: true,
            monitoringStartedAt: TestTime.offset(-60),
            isSmartDetectionEnabled: true,
            storeSetupFailure: nil,
            traceSummary: .empty
        )

        // Assert — zero would read as "just heard something", which is the opposite.
        #expect(report.monitoringAge == 60)
        #expect(report.timeSinceLastDetectionInput == nil)
    }
}
