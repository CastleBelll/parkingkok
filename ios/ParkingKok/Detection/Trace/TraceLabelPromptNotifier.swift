import Foundation
import UserNotifications

/// What `TraceRecorder` is allowed to know about prompting for a label.
///
/// One method, no return value, never throws: a closed session is already on disk when
/// this is called, and a notification that cannot be posted must not be able to break the
/// recording path (docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §9 best-effort).
protocol TraceLabelPrompting: Sendable {
    /// Fire-and-forget. Whether anything is actually shown is the implementation's
    /// business, including deciding that permission is missing.
    func requestPrompt(_ prompt: TraceLabelPrompt)
}

/// The notification system, reduced to the two things prompting needs.
///
/// Exists so the decision in `TraceLabelPrompter` — post, or count a suppression — is
/// testable. `UNUserNotificationCenter.current()` cannot be reached from a unit test
/// without the real notification service behind it.
protocol LabelPromptDelivering: Sendable {
    /// Whether the app may show an alert at all. `false` covers both "never asked" and
    /// "user said no"; neither is an error and neither is retried.
    func isAuthorized() async -> Bool
    /// Registers the actions and posts. Swallows its own failures — see the protocol above.
    func deliver(_ prompt: TraceLabelPrompt) async
}

/// The names the notification and the tap that comes back have to agree on.
///
/// Its own category identifier, never `parking_detection` or the M3 candidate category:
/// this asks about a trip that already ended, and a diagnostics prompt sharing a channel
/// with a product notification would make "the user muted the wrong one" unanswerable.
enum TraceLabelPromptAction {
    static let categoryIdentifier = "pk.diagnostics.trace_label_prompt"
    static let sessionIdKey = "pk.diagnostics.trace_session_id"
    private static let actionPrefix = "pk.diagnostics.trace_label."

    static func identifier(for mode: TraceMode) -> String {
        actionPrefix + mode.rawValue
    }

    /// `nil` for anything that is not one of our label actions — the body tap
    /// (`UNNotificationDefaultActionIdentifier`), a dismissal, or an identifier left over
    /// from a build that offered a different set.
    static func mode(forActionIdentifier identifier: String) -> TraceMode? {
        guard identifier.hasPrefix(actionPrefix) else { return nil }
        return TraceMode(rawValue: String(identifier.dropFirst(actionPrefix.count)))
    }
}

/// Decides whether a closed session is worth a notification, and counts it when it is not.
///
/// The decision is the whole of this type, which is why it is separate from the delivery it
/// drives: a missing permission has to be *visible* rather than silent, because a field
/// weekend that collected nothing would otherwise look exactly like a weekend nobody
/// travelled (§9 "조용히 버리지 마라").
struct TraceLabelPrompter: TraceLabelPrompting {
    private let delivery: any LabelPromptDelivering
    /// Bumps the persisted counter the diagnostics report reads. A closure rather than the
    /// store itself: this needs one number incremented, not a trace store.
    private let onSuppressed: @Sendable () -> Void

    init(delivery: any LabelPromptDelivering, onSuppressed: @escaping @Sendable () -> Void) {
        self.delivery = delivery
        self.onSuppressed = onSuppressed
    }

    /// Hands the work to an unstructured `Task`, because the recorder's callers are
    /// synchronous and a session must be considered closed the instant its file is.
    ///
    /// Unstructured on purpose: the enclosing task is a detection callback that may finish
    /// immediately, and a child task would be cancelled with it. The cost is that a process
    /// suspended in the next instant loses the prompt — acceptable for instrumentation, and
    /// the alternative would be making the recording path wait on the notification service.
    func requestPrompt(_ prompt: TraceLabelPrompt) {
        Task { await resolvePrompt(prompt) }
    }

    /// The same decision, awaited. `requestPrompt` is exactly this plus a `Task`, so a test
    /// asserts on the outcome instead of racing an unstructured task.
    func resolvePrompt(_ prompt: TraceLabelPrompt) async {
        guard await delivery.isAuthorized() else {
            onSuppressed()
            return
        }
        await delivery.deliver(prompt)
    }
}

/// Posts through `UNUserNotificationCenter`.
///
/// Four actions, which is what iOS shows on an expanded notification; the ordering in
/// `TraceLabelPrompt.offeredModes` is what decides who gets cut if that ever shrinks.
/// `.foreground` is deliberately *not* set on any of them — the point of this whole change
/// is that labelling costs one tap and never an app launch.
struct UserNotificationLabelPromptDelivery: LabelPromptDelivering {
    /// iOS shows at most four actions on an expanded notification.
    static let maximumActionCount = 4

    /// Reached through `current()` on each use rather than stored: the class is not
    /// `Sendable`, and the seam that matters for testing is `LabelPromptDelivering` itself.
    private var center: UNUserNotificationCenter {
        .current()
    }

    func isAuthorized() async -> Bool {
        switch await center.notificationSettings().authorizationStatus {
        case .authorized, .provisional, .ephemeral: true
        case .denied, .notDetermined: false
        @unknown default: false
        }
    }

    func deliver(_ prompt: TraceLabelPrompt) async {
        // Registered here rather than at launch so the whole feature is one file to delete.
        // `setNotificationCategories` replaces the set, which is safe only while this is
        // the app's sole category — the M3 candidate notification will need a single
        // registration point, and this is the line that has to move then.
        center.setNotificationCategories([Self.category])

        let content = UNMutableNotificationContent()
        content.title = TraceLabelPrompt.title
        content.body = prompt.body()
        content.categoryIdentifier = TraceLabelPromptAction.categoryIdentifier
        // The session id is the only payload. A UUID, never a place.
        content.userInfo = [TraceLabelPromptAction.sessionIdKey: prompt.sessionId.uuidString]
        content.interruptionLevel = .active

        let request = UNNotificationRequest(
            // One notification per session: a second close cannot overwrite the first, and
            // the tap handler knows which trace it is answering about.
            identifier: "\(TraceLabelPromptAction.categoryIdentifier).\(prompt.sessionId.uuidString)",
            content: content,
            trigger: nil
        )
        do {
            try await center.add(request)
        } catch {
            // Best-effort: the trace is already on disk and the in-app screen still works.
            let nsError = error as NSError
            AppLog.detection.error(
                "trace label prompt failed: \(nsError.domain, privacy: .public)(\(nsError.code, privacy: .public))"
            )
        }
    }

    private static var category: UNNotificationCategory {
        let actions = TraceLabelPrompt.offeredModes.prefix(maximumActionCount).map { mode in
            UNNotificationAction(
                identifier: TraceLabelPromptAction.identifier(for: mode),
                title: mode.promptActionTitle,
                options: []
            )
        }
        return UNNotificationCategory(
            identifier: TraceLabelPromptAction.categoryIdentifier,
            actions: Array(actions),
            intentIdentifiers: [],
            options: []
        )
    }
}

/// Turns a tapped action into a stored label.
///
/// The notification arrives in a process that may have been launched for it, so this is
/// wired in `DetectionRuntime.bootstrap` and holds the store directly: there is no view
/// model and no screen involved in a one-tap label.
final class TraceLabelPromptResponder: NSObject, UNUserNotificationCenterDelegate {
    private let store: any TraceStoring

    init(store: any TraceStoring) {
        self.store = store
    }

    /// The pure half, so the mapping from an action identifier to a written label is
    /// testable without a `UNNotificationResponse` (which cannot be constructed).
    ///
    /// @return whether a label was written. `false` for the body tap — that opens the app,
    /// where the labelling screen takes over — and for a session the store no longer has.
    @discardableResult
    func applyLabel(actionIdentifier: String, sessionId: UUID?) -> Bool {
        guard let sessionId,
              let mode = TraceLabelPromptAction.mode(forActionIdentifier: actionIdentifier)
        else { return false }
        do {
            try store.updateLabel(TraceLabelPrompt.label(for: mode), for: sessionId)
            return true
        } catch {
            // A session evicted by the rolling cap between the prompt and the tap is an
            // ordinary outcome, not a failure worth breaking the callback for.
            AppLog.detection.notice("trace label prompt tap could not be applied: \(String(describing: error))")
            return false
        }
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let userInfo = response.notification.request.content.userInfo
        let sessionId = (userInfo[TraceLabelPromptAction.sessionIdKey] as? String).flatMap(UUID.init(uuidString:))
        applyLabel(actionIdentifier: response.actionIdentifier, sessionId: sessionId)
        completionHandler()
    }
}
