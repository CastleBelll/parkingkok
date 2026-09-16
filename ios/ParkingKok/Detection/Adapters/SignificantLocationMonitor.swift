import CoreLocation
import Foundation

@MainActor
protocol SignificantLocationMonitorDelegate: AnyObject {
    func monitorDidChangeAuthorization(_ authorization: LocationAuthorization)
    func monitorDidReceiveLocation(_ sample: LocationQualitySample)
    func monitorDidFail(_ description: String)
}

/// The low-power travel trigger: `startMonitoringSignificantLocationChanges()`
/// (docs/04_IOS_IMPLEMENTATION.md §3 IDLE).
///
/// Significant-change monitoring is the only Core Location mode that relaunches a
/// terminated app in the background, and it does so without the continuous high-accuracy
/// session that docs/00_CORE_RULES.md Background forbids. It also needs no
/// `UIBackgroundModes` entry — the bounded driving session in M0A-2 is what will.
///
/// `@MainActor` because the manager must be created on a run loop and its delegate
/// callbacks arrive there. This type does no detection reasoning: it normalizes and
/// forwards (docs/03_SYSTEM_ARCHITECTURE.md §4).
@MainActor
final class SignificantLocationMonitor: NSObject {
    private let manager: CLLocationManager

    weak var delegate: (any SignificantLocationMonitorDelegate)?

    private(set) var isMonitoring = false

    /// Instantiating this type wires the delegate immediately, which is the part
    /// docs/04 §3 requires to happen synchronously on a location relaunch.
    init(manager: CLLocationManager = CLLocationManager()) {
        self.manager = manager
        super.init()
        manager.delegate = self
    }

    var authorization: LocationAuthorization {
        LocationAuthorization(manager.authorizationStatus)
    }

    /// Re-registers the service. Idempotent — Core Location tolerates repeat calls, and
    /// a relaunch must call it again to receive the event that woke the app.
    func startMonitoring() {
        guard authorization.allowsSignificantLocationMonitoring else {
            isMonitoring = false
            return
        }
        manager.startMonitoringSignificantLocationChanges()
        isMonitoring = true
    }

    func stopMonitoring() {
        manager.stopMonitoringSignificantLocationChanges()
        isMonitoring = false
    }

    func requestWhenInUseAuthorization() {
        manager.requestWhenInUseAuthorization()
    }

    /// Only ever called after the Smart Detection opt-in (docs/04 §4). The caller owns
    /// that gate; see `PermissionRequestPolicy`.
    func requestAlwaysAuthorization() {
        manager.requestAlwaysAuthorization()
    }
}

extension SignificantLocationMonitor: CLLocationManagerDelegate {
    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        // Core Location delivers every callback on the run loop the manager was created
        // on, which is the main run loop here — hence `assumeIsolated` rather than a hop.
        // Reading the status first keeps the non-Sendable manager out of the closure.
        let authorization = LocationAuthorization(manager.authorizationStatus)
        MainActor.assumeIsolated {
            AppLog.detection.notice("location authorization -> \(authorization.rawValue, privacy: .public)")
            delegate?.monitorDidChangeAuthorization(authorization)
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        // Map to the domain type here: past this line the coordinate does not exist.
        let samples = locations.map(LocationQualitySample.init)
        MainActor.assumeIsolated {
            for sample in samples where sample.isValid {
                delegate?.monitorDidReceiveLocation(sample)
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: any Error) {
        let nsError = error as NSError
        let description = "\(nsError.domain)(\(nsError.code))"
        MainActor.assumeIsolated {
            AppLog.detection.error("location manager failed: \(description, privacy: .public)")
            delegate?.monitorDidFail(description)
        }
    }
}
