import CoreMotion
import Foundation

enum MotionHistoryError: Error, Equatable {
    case unavailable
    case notAuthorized
    case queryFailed(String)

    var diagnosticDescription: String {
        switch self {
        case .unavailable: "motion activity unavailable on this device"
        case .notAuthorized: "motion permission not granted"
        case let .queryFailed(reason): "query failed: \(reason)"
        }
    }
}

protocol MotionHistoryProviding: Sendable {
    var authorization: MotionAuthorization { get }
    var isHistoryAvailable: Bool { get }

    /// Normalized samples in ascending timestamp order.
    func samples(in window: MotionHistoryWindow) async throws -> [MotionSample]
}

/// `CMMotionActivityManager.queryActivityStarting(from:to:)` wrapped for async use
/// (docs/04_IOS_IMPLEMENTATION.md §5 use 2, §6 step 3).
///
/// An actor because `CMMotionActivityManager` is not `Sendable` and a query must not
/// overlap itself. Activities are normalized to `MotionSample` inside the completion
/// handler so no Core Motion object ever crosses an isolation boundary.
actor CoreMotionHistoryProvider: MotionHistoryProviding {
    private let manager = CMMotionActivityManager()
    private let queue: OperationQueue

    init() {
        let queue = OperationQueue()
        queue.name = "kr.parkingpin.motion-history"
        queue.maxConcurrentOperationCount = 1
        self.queue = queue
    }

    nonisolated var authorization: MotionAuthorization {
        MotionAuthorization(CMMotionActivityManager.authorizationStatus())
    }

    nonisolated var isHistoryAvailable: Bool {
        CMMotionActivityManager.isActivityAvailable()
    }

    func samples(in window: MotionHistoryWindow) async throws -> [MotionSample] {
        guard isHistoryAvailable else { throw MotionHistoryError.unavailable }
        guard authorization.allowsHistoryQuery else { throw MotionHistoryError.notAuthorized }
        guard window.start < window.end else { return [] }

        let samples: [MotionSample] = try await withCheckedThrowingContinuation { continuation in
            manager.queryActivityStarting(from: window.start, to: window.end, to: queue) { activities, error in
                if let error {
                    let nsError = error as NSError
                    continuation.resume(
                        throwing: MotionHistoryError.queryFailed("\(nsError.domain)(\(nsError.code))")
                    )
                    return
                }
                continuation.resume(returning: (activities ?? []).map(MotionSample.init))
            }
        }
        return samples.sorted { $0.timestamp < $1.timestamp }
    }
}
