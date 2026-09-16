import UIKit

/// Lifecycle plumbing only.
///
/// docs/04_IOS_IMPLEMENTATION.md §2: the delegate reads the launch reason and forwards to
/// `DetectionRuntime`. No detection decision is made here.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Core Location sets this key when a significant change relaunched a terminated
        // app. The service must be re-registered before this method returns (docs/04 §3),
        // which is why `bootstrap` does its Core Location work synchronously.
        let relaunchedByLocation = launchOptions?[.location] != nil
        DetectionRuntime.shared.bootstrap(
            launchReason: relaunchedByLocation ? .significantLocationChange : .userInitiated
        )
        return true
    }
}
