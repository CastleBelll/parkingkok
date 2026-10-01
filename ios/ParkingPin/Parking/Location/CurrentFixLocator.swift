import CoreLocation
import Foundation

/// One fix, now, for the save the user just asked for.
///
/// Separate from the detection stack's drive capture on purpose: that capture is a
/// *drive*, bounded and battery-gated (docs/05 §19), and a manual save is a single moment.
/// Starting the drive session to answer it would be the 24-hour-tracking shape docs/00
/// forbids, and stopping it again immediately would be worse.
///
/// `@MainActor` because `CLLocationManager` is not `Sendable` and Swift 6 is right about
/// that: the manager, its delegate callbacks and the continuation they resume all have to
/// live on one actor, and `ParkingLocationProviding` above it is already on this one.
@MainActor
protocol OneShotLocating: Sendable {
    /// The current fix, or `nil` — no authorization, a timeout, or Core Location failing.
    ///
    /// **Never prompts.** FR-001 says a manual save works with no permission at all, so an
    /// undetermined status is a `nil` here and not a dialog in the middle of saving.
    func currentFix(timeout: TimeInterval) async -> CLLocation?
}

/// A short burst of `CLLocationManager` updates, wrapped so a caller can `await` one fix.
///
/// **Not `requestLocation()`.** That API waits for the best fix it can get before it
/// answers, which on an iPhone on 2026-10-01 was 10 s for a 12 m fix — ten seconds in which
/// the saved parking showed `위치 없음` and read as a save that had lost its location
/// ("여전히 직접 입력하면 위치가 저장이 안되네"). Updates arrive coarse first and sharpen, so this
/// answers as soon as one is [goodEnoughAccuracy], or with the best seen so far once
/// [settleDelay] has passed, and only gives up at the caller's timeout.
@MainActor
final class CoreLocationOneShotLocator: NSObject, OneShotLocating {
    /// How long a save's location may take to arrive. Nobody waits on it: the record is
    /// written first and the fix is attached afterwards (`ParkingModel.attachCurrentFix`).
    ///
    /// It was 8 s, sized for when the save *did* wait. Measured on an iPhone on 2026-09-24:
    /// a 10 m fix arrived 10 s after a save, 2 s after the deadline, and was thrown away,
    /// so the record kept no location. The bound that still matters is the walk: a fix
    /// taken later is where the phone is, not the car, and 20 s of walking is about 25 m.
    static let defaultTimeout: TimeInterval = 20

    /// A fix this good answers at once: waiting longer buys nothing a car park can use.
    static let goodEnoughAccuracy: CLLocationAccuracy = 20

    /// After this long the best fix so far is the answer, if it is usable at all.
    static let settleDelay: TimeInterval = 5

    /// What [settleDelay] will settle for — the same bound the provider applies.
    static let usableAccuracy: CLLocationAccuracy = 100

    private let manager = CLLocationManager()
    private var continuation: CheckedContinuation<CLLocation?, Never>?
    private var deadlineTasks: [Task<Void, Never>] = []
    private var best: CLLocation?
    private var requestedAt = Date.distantPast

    /// `startUpdatingLocation` may hand back the OS's cached fix first; one older than this
    /// before the request is where the phone was, not where the car is.
    static let maximumCachedAge: TimeInterval = 5

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
    }

    func currentFix(timeout: TimeInterval = defaultTimeout) async -> CLLocation? {
        let status = manager.authorizationStatus
        guard status == .authorizedWhenInUse || status == .authorizedAlways else {
            #if PK_DEV
                SaveLocationDiagnostics.note("fix", "unauthorized status=\(status.rawValue)")
            #endif
            return nil
        }
        // One at a time. A second tap while the first is in flight would otherwise leave a
        // continuation nobody resumes.
        guard continuation == nil else {
            #if PK_DEV
                SaveLocationDiagnostics.note("fix", "busy")
            #endif
            return nil
        }

        return await withCheckedContinuation { continuation in
            self.continuation = continuation
            best = nil
            requestedAt = Date()
            deadlineTasks = [
                Task { [weak self] in
                    try? await Task.sleep(for: .seconds(Self.settleDelay))
                    guard !Task.isCancelled else { return }
                    self?.settleIfUsable()
                },
                Task { [weak self] in
                    try? await Task.sleep(for: .seconds(timeout))
                    guard !Task.isCancelled else { return }
                    #if PK_DEV
                        SaveLocationDiagnostics.note("fix", "timeout")
                    #endif
                    self?.finish(self?.usableBest)
                }
            ]
            manager.startUpdatingLocation()
        }
    }

    private var usableBest: CLLocation? {
        best.flatMap { $0.horizontalAccuracy <= Self.usableAccuracy ? $0 : nil }
    }

    fileprivate func consider(_ location: CLLocation) {
        guard continuation != nil, location.horizontalAccuracy > 0,
              location.timestamp >= requestedAt.addingTimeInterval(-Self.maximumCachedAge)
        else { return }
        if best.map({ location.horizontalAccuracy < $0.horizontalAccuracy }) ?? true {
            best = location
        }
        if location.horizontalAccuracy <= Self.goodEnoughAccuracy {
            finish(location)
        }
    }

    private func settleIfUsable() {
        guard let usableBest else { return }
        finish(usableBest)
    }

    /// Resumes at most once, whichever of Core Location and the deadlines arrives first.
    fileprivate func finish(_ location: CLLocation?) {
        guard let pending = continuation else { return }
        continuation = nil
        manager.stopUpdatingLocation()
        deadlineTasks.forEach { $0.cancel() }
        deadlineTasks = []
        best = nil
        pending.resume(returning: location)
    }
}

/// `@MainActor` on the conformance itself: Core Location delivers these on the queue the
/// manager was created on, which is this actor, and Swift 6 wants that stated rather than
/// assumed.
extension CoreLocationOneShotLocator: @MainActor CLLocationManagerDelegate {
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        #if PK_DEV
            if let last = locations.last {
                SaveLocationDiagnostics.note("fixRaw", "accuracy=\(Int(last.horizontalAccuracy))m")
            }
        #endif
        locations.forEach(consider)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: any Error) {
        // The code only. A Core Location error carries no coordinate, but its description
        // has carried region identifiers before, and docs/09 §11 keeps those out.
        // `locationUnknown` is "no fix yet, still trying" while updates are running.
        if (error as? CLError)?.code == .locationUnknown {
            return
        }
        AppLog.detection.info("one-shot fix failed: \((error as NSError).code, privacy: .public)")
        #if PK_DEV
            SaveLocationDiagnostics.note("fix", "failed code=\((error as NSError).code)")
        #endif
        finish(usableBest)
    }
}
