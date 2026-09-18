import Foundation

/// The P0 diagnostics, flattened for export beside the checkpoint.
///
/// Why it exists: the motion samples, authorization statuses and drop counters that
/// prove the detection path worked lived only in memory and in `.info` log lines, so
/// reading them back from a device needed `sudo log collect`. Written as a file instead,
/// it comes off the device over the same path the checkpoint already uses, with no root.
///
/// **A hand-written projection, never an encoding of the live types.** `DetectionCheckpoint`
/// is `Codable` and carries `lastReliableLocation`, which holds real coordinates now that
/// M0A-2 selects one. This file is copied off the device by design, so encoding the
/// checkpoint wholesale would walk parking coordinates straight past
/// `docs/00_CORE_RULES.md` Privacy. Every field below is listed by hand; the
/// coordinate-bearing ones are deliberately absent, and a test on the encoded bytes
/// enforces it. **Adding a coordinate here is never the fix for a failing test.**
struct DiagnosticsReport: Sendable, Equatable, Codable {
    /// Bumped to 5 by the §7 distance-clause instrumentation; 4 was the movement-evidence
    /// counters before it.
    static let schemaVersion = 7

    var schemaVersion: Int = DiagnosticsReport.schemaVersion
    var generatedAt: Date

    // Launch and rehydration
    var launchReason: String
    var rehydratedAt: Date?
    var checkpointLoad: String
    var checkpointAge: TimeInterval?
    var isBeyondMotionRetention: Bool

    // Checkpoint, projected
    var state: String?
    var revision: Int?
    var stateEnteredAt: Date?
    var lastAutomotiveAt: Date?
    var lastLocationAt: Date?
    var travelDistanceEstimate: Double?
    var hasCandidate: Bool
    /// Presence and quality of the reliable fix — never where it was.
    var hasReliableLocation: Bool
    var reliableLocationCapturedAt: Date?
    var reliableLocationAccuracy: Double?

    // Motion
    var motionWindowStart: Date?
    var motionWindowEnd: Date?
    var motionSampleCount: Int
    var motionFailure: String?
    var motionSamples: [MotionSample]

    // Bounded driving session (docs/04 §3 DRIVING, docs/05 §7)
    var isCapturingDrivingLocation: Bool
    var drivingSessionStartedAt: Date?
    var drivingSessionCount: Int
    var drivingSessionResumedFromCheckpoint: Bool
    var drivingConfirmedAt: Date?
    var lastDrivingSessionEndReason: String?
    var lastDrivingSessionEndedAt: Date?
    var drivingFixCount: Int
    var drivingMovingSampleCount: Int
    /// docs/05 §7 movement evidence, instrumented. `speedMissingCount == fixCount` with
    /// `movingSampleCount == 0` is the exact signature of the defect these fields were
    /// added for: the speed-only rule could not confirm a drive underground.
    var speedAvailableCount: Int
    var speedMissingCount: Int
    var derivedMovingSampleCount: Int
    /// Why the distance fallback last declined a fix, or absent if it never has.
    var movementEvidenceRejectReason: String?
    var drivingOutlierDropCount: Int
    var drivingDistanceMeters: Double
    /// Legs §7's noise floor kept out of `drivingDistanceMeters`. Same name and meaning on
    /// Android, so one number can be compared across a pair of field runs.
    var distanceNoiseFloorRejectCount: Int
    /// How often a fix was accepted as `lastReliableLocation` — never which fix.
    var reliableLocationUpdateCount: Int
    var reliableLocationRejectCount: Int
    var lastReliableLocationRejection: String?
    var lastVehicleEvidenceAt: Date?
    var lastVehicleEvidenceConfidence: String?
    var captureFailure: String?

    // Trace recording (docs/05 §9). Counts only — the traces themselves are separate
    // files, and the point of these numbers is to tell whether recording is working
    // without retrieving any of them.
    var traceSessionCount: Int
    var traceEventCount: Int
    /// Sessions the rolling cap discarded. §9 requires the eviction to be visible; a
    /// silently shrinking history would look like recording that never happened.
    var traceDroppedSessionCount: Int
    /// Sessions discarded at rotation for holding one event or none (§9 "비생존 세션은
    /// 버린다"). Separate from the rolling-cap count on purpose: a rolling-cap eviction
    /// means the device is recording more than it can hold, while this climbing means the
    /// boundary is cutting sessions where there was nothing to cut — five of the nine
    /// September 2026 field sessions were single events. §9: "조용히 버리지 마라."
    var traceNonViableDropCount: Int
    var traceUnlabeledSessionCount: Int
    /// Closed sessions that were worth a label prompt and did not get one, because
    /// notifications are not permitted. The number that tells a field weekend with no
    /// labels apart from a field weekend with no travel. Same name on Android.
    var traceLabelPromptSuppressedCount: Int
    /// Sessions carrying a gap measurement, so the three numbers below can be read as a
    /// sample size rather than as a claim about every trace on disk.
    var traceMeasuredSessionCount: Int
    /// The §9 gap aggregate, over the sessions still on disk. **Instrumentation for a
    /// decision not yet taken**: the 30-minute idle gap came from a single day and missed
    /// by 66 seconds, and it does not move until these have accumulated.
    var traceMaxGapMillis: Int64
    var traceSessionsOver10MinGapCount: Int
    var traceSessionsOver20MinGapCount: Int
    /// Location observations refused as replays of an instant already recorded. Zero is
    /// the expected reading; a climbing count with a growing trace is Core Location
    /// re-delivering cached fixes and being ignored, which is the point.
    var traceReplayDropCount: Int
    var traceFailure: String?

    // Location quality
    var significantChangeCount: Int
    var lastLocationAccuracy: Double?
    var staleLocationDropCount: Int
    var lastStaleLocationAge: TimeInterval?
    var supersededLocationDropCount: Int
    var locationFailure: String?

    // Permissions and wiring
    var locationAuthorization: String
    var motionAuthorization: String
    var isMotionHistoryAvailable: Bool
    var isMonitoringSignificantChanges: Bool
    /// How long monitoring has been up, and how long since any detection input arrived.
    ///
    /// "Monitoring" and "receiving" are different claims and this file only made the
    /// first. A long age beside a longer silence is the shape worth suspecting; a short
    /// silence just means the pipeline is quiet.
    var monitoringAge: TimeInterval?
    var timeSinceLastDetectionInput: TimeInterval?
    var isSmartDetectionEnabled: Bool
    var storeSetupFailure: String?
    var lastPersistError: String?

    init(
        snapshot: RehydrationSnapshot,
        now: Date,
        locationAuthorization: LocationAuthorization,
        motionAuthorization: MotionAuthorization,
        isMotionHistoryAvailable: Bool,
        isMonitoringSignificantChanges: Bool,
        monitoringStartedAt: Date?,
        isSmartDetectionEnabled: Bool,
        storeSetupFailure: String?,
        traceSummary: TraceSummary
    ) {
        generatedAt = now

        launchReason = snapshot.launchReason.rawValue
        rehydratedAt = snapshot.rehydratedAt
        checkpointLoad = Self.describe(snapshot.checkpointLoad)
        checkpointAge = snapshot.checkpointAge
        isBeyondMotionRetention = snapshot.isBeyondMotionRetention

        let checkpoint = snapshot.currentCheckpoint
        state = checkpoint?.state.rawValue
        revision = checkpoint?.revision
        stateEnteredAt = checkpoint?.stateEnteredAt
        lastAutomotiveAt = checkpoint?.lastAutomotiveAt
        lastLocationAt = checkpoint?.lastLocationAt
        travelDistanceEstimate = checkpoint?.travelDistanceEstimate
        hasCandidate = checkpoint?.candidateId != nil
        hasReliableLocation = checkpoint?.lastReliableLocation != nil
        reliableLocationCapturedAt = checkpoint?.lastReliableLocation?.capturedAt
        reliableLocationAccuracy = checkpoint?.lastReliableLocation?.horizontalAccuracy

        motionWindowStart = snapshot.motionWindow?.start
        motionWindowEnd = snapshot.motionWindow?.end
        motionSampleCount = snapshot.motionSamples.count
        motionFailure = snapshot.motionFailure
        motionSamples = snapshot.motionSamples

        isCapturingDrivingLocation = snapshot.isCapturingDrivingLocation
        drivingSessionStartedAt = snapshot.drivingSessionStartedAt
        drivingSessionCount = snapshot.drivingSessionCount
        drivingSessionResumedFromCheckpoint = snapshot.drivingSessionResumedFromCheckpoint
        drivingConfirmedAt = snapshot.drivingConfirmedAt
        lastDrivingSessionEndReason = snapshot.lastDrivingSessionEndReason?.rawValue
        lastDrivingSessionEndedAt = snapshot.lastDrivingSessionEndedAt
        drivingFixCount = snapshot.drivingFixCount
        drivingMovingSampleCount = snapshot.drivingMovingSampleCount
        speedAvailableCount = snapshot.drivingSpeedAvailableCount
        speedMissingCount = snapshot.drivingSpeedMissingCount
        derivedMovingSampleCount = snapshot.drivingDerivedMovingSampleCount
        movementEvidenceRejectReason = snapshot.movementEvidenceRejectReason?.rawValue
        drivingOutlierDropCount = snapshot.drivingOutlierDropCount
        drivingDistanceMeters = snapshot.drivingDistanceMeters
        distanceNoiseFloorRejectCount = snapshot.drivingDistanceNoiseFloorRejectCount
        reliableLocationUpdateCount = snapshot.reliableLocationUpdateCount
        reliableLocationRejectCount = snapshot.reliableLocationRejectCount
        lastReliableLocationRejection = snapshot.lastReliableLocationRejection?.rawValue
        lastVehicleEvidenceAt = snapshot.lastVehicleEvidenceAt
        lastVehicleEvidenceConfidence = snapshot.lastVehicleEvidenceConfidence?.rawValue
        captureFailure = snapshot.captureFailure

        traceSessionCount = traceSummary.sessionCount
        traceEventCount = traceSummary.eventCount
        traceDroppedSessionCount = traceSummary.droppedSessionCount
        traceNonViableDropCount = traceSummary.nonViableDropCount
        traceUnlabeledSessionCount = traceSummary.unlabeledSessionCount
        traceLabelPromptSuppressedCount = traceSummary.labelPromptSuppressedCount
        traceMeasuredSessionCount = traceSummary.measuredSessionCount
        traceMaxGapMillis = traceSummary.maxGapMillis
        traceSessionsOver10MinGapCount = traceSummary.sessionsOver10MinGapCount
        traceSessionsOver20MinGapCount = traceSummary.sessionsOver20MinGapCount
        traceReplayDropCount = snapshot.traceReplayDropCount
        traceFailure = snapshot.traceFailure

        significantChangeCount = snapshot.significantChangeCount
        lastLocationAccuracy = snapshot.lastLocationAccuracy
        staleLocationDropCount = snapshot.staleLocationDropCount
        lastStaleLocationAge = snapshot.lastStaleLocationAge
        supersededLocationDropCount = snapshot.supersededLocationDropCount
        locationFailure = snapshot.locationFailure

        self.locationAuthorization = locationAuthorization.rawValue
        self.motionAuthorization = motionAuthorization.rawValue
        self.isMotionHistoryAvailable = isMotionHistoryAvailable
        self.isMonitoringSignificantChanges = isMonitoringSignificantChanges
        monitoringAge = monitoringStartedAt.map { now.timeIntervalSince($0) }
        // Any input counts: a motion edge, a significant change, or a bounded fix. The
        // question is whether the stack is hearing anything at all.
        timeSinceLastDetectionInput = [
            snapshot.motionSamples.last?.timestamp,
            snapshot.lastLocationAt,
            snapshot.lastVehicleEvidenceAt
        ]
        .compactMap(\.self)
        .max()
        .map { now.timeIntervalSince($0) }
        self.isSmartDetectionEnabled = isSmartDetectionEnabled
        self.storeSetupFailure = storeSetupFailure
        lastPersistError = snapshot.lastPersistError
    }

    private static func describe(_ load: DetectionCheckpointLoadResult) -> String {
        switch load {
        case let .restored(checkpoint): "restored(rev \(checkpoint.revision))"
        case .absent: "absent"
        case let .failed(failure): "failed(\(failure.diagnosticDescription))"
        }
    }
}

/// Writes the report beside the checkpoint so it inherits the same directory protection
/// class and comes off the device over the same path.
///
/// Best-effort by design: a diagnostics file that fails to write must never take down a
/// detection callback. The failure surfaces in the next report instead.
protocol DiagnosticsReportStoring: Sendable {
    func write(_ report: DiagnosticsReport) throws
}

struct FileDiagnosticsReportStore: DiagnosticsReportStoring {
    private static let fileName = "diagnostics.json"

    private let fileURL: URL

    init(fileURL: URL) {
        self.fileURL = fileURL
    }

    /// Beside `checkpoint.json`, so the directory's protection class covers both.
    static func defaultFileURL() throws -> URL {
        try FileDetectionCheckpointStore.defaultFileURL()
            .deletingLastPathComponent()
            .appending(path: fileName, directoryHint: .notDirectory)
    }

    func write(_ report: DiagnosticsReport) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        let data = try encoder.encode(report)
        try data.write(to: fileURL, options: [.atomic])
    }
}
