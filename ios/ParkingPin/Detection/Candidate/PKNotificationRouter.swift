import Foundation
import UserNotifications

/// One feature's answer to a tapped notification.
///
/// The app has two categories — the product's parking candidate and the diagnostics label
/// prompt — and `UNUserNotificationCenter` has exactly one delegate. This is what lets both
/// exist without either knowing about the other.
@MainActor
protocol NotificationResponding: AnyObject {
    var categoryIdentifier: String { get }
    /// Runs on the main actor, and must return quickly: docs/04_IOS_IMPLEMENTATION.md §8
    /// requires the response handler to complete fast, and the process may have been
    /// launched only to run this.
    ///
    /// `userInfo` is `[String: String]` rather than the SDK's `[AnyHashable: Any]`. Both
    /// features put a UUID string in it and read a UUID string out, and a `Sendable`
    /// dictionary is what lets the response cross from the delegate callback to the main
    /// actor at all under Swift 6 — `UNNotificationResponse` itself cannot.
    func handle(actionIdentifier: String, userInfo: [String: String])
}

/// The app's single `UNUserNotificationCenterDelegate`, which fans a response out to
/// whichever feature owns the category it arrived on.
///
/// ### Why the handlers are provided lazily
/// A tap can launch the process. At that instant `DetectionRuntime.bootstrap` has run —
/// the delegate must be installed before launch returns, or the response is lost — but the
/// parking store has not been opened, because opening it is UI-time work. A provider
/// closure lets the candidate handler be built at tap time, in a process that may never
/// draw a frame, and lets the diagnostics handler be the object that already exists.
@MainActor
final class PKNotificationRouter: NSObject, UNUserNotificationCenterDelegate {
    /// One per process. `UNUserNotificationCenter` holds its delegate weakly, so something
    /// has to own this for the app's lifetime and a tap that launched the app cannot be
    /// raced by deallocation.
    static let shared = PKNotificationRouter()

    /// Where a text action's typing is put before it crosses to the main actor.
    /// `nonisolated` because the delegate callback that writes it is.
    nonisolated static let userTextKey = "pk.notification.user_text"

    private var providers: [() -> (any NotificationResponding)?] = []

    /// Adds a handler. Every call site is a composition root rather than a feature, so
    /// the set is readable in one place.
    func register(_ provider: @escaping () -> (any NotificationResponding)?) {
        providers.append(provider)
    }

    /// A banner for a notification that arrives while the app is open.
    ///
    /// Without it iOS shows nothing in the foreground, and the candidate would be
    /// announced only to a user who was not looking — while the one who *is* looking gets
    /// silence and a surprise record. docs/10 §7 makes the guess something the user
    /// answers, not something that happens to them.
    nonisolated func userNotificationCenter(
        _: UNUserNotificationCenter,
        willPresent _: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }

    /// The completion-handler form, not the `async` one.
    ///
    /// `UNNotificationResponse` is not `Sendable`, so nothing here may carry it across an
    /// isolation boundary. Everything the handlers need is read out as strings first, and
    /// only those cross — which is also why `NotificationResponding` takes a
    /// `[String: String]`. These callbacks are documented to arrive on the main thread,
    /// and `assumeIsolated` states that rather than assuming it silently.
    nonisolated func userNotificationCenter(
        _: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let content = response.notification.request.content
        let category = content.categoryIdentifier
        let actionIdentifier = response.actionIdentifier
        var userInfo: [String: String] = content.userInfo.reduce(into: [:]) { result, entry in
            guard let key = entry.key as? String, let value = entry.value as? String else { return }
            result[key] = value
        }
        if let text = (response as? UNTextInputNotificationResponse)?.userText {
            userInfo[Self.userTextKey] = text
        }

        MainActor.assumeIsolated {
            dispatch(category: category, actionIdentifier: actionIdentifier, userInfo: userInfo)
        }
        completionHandler()
    }

    /// The main-actor half, separate so a test can drive it without a
    /// `UNNotificationResponse` — which cannot be constructed.
    func dispatch(category: String, actionIdentifier: String, userInfo: [String: String]) {
        guard let handler = providers.lazy.compactMap({ $0() }).first(where: {
            $0.categoryIdentifier == category
        }) else {
            AppLog.detection.notice("notification response for unknown category")
            return
        }
        handler.handle(actionIdentifier: actionIdentifier, userInfo: userInfo)
    }
}
