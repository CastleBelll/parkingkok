import FirebaseAnalytics
import Foundation

/// The payload's parameters in the shapes a Firebase event parameter may hold.
///
/// Split out of `FirebaseAnalyticsSink` because a mapping is exactly where a value can
/// quietly change meaning — or disappear — and this is the last hop before one leaves the
/// device. `AnalyticsTests` holds it against docs/17 §3 alongside the payload itself.
///
/// A `Flag` becomes `"true"` / `"false"` rather than a boolean: Android must send the same
/// strings, because Firebase drops a boolean put into an Android event bundle, and the two
/// platforms have to land in one comparable column (docs/05 parity).
func firebaseParameters(_ payload: AnalyticsPayload) -> [String: Any] {
    payload.parameters.mapValues { value in
        switch value {
        case let .string(text): text
        case let .int(number): number
        case let .bool(flag): String(flag)
        }
    }
}

/// The Firebase transport (docs/07 §2 "Analytics 전송 수단").
///
/// It sees an `AnalyticsPayload` and nothing else, which is the whole point of the seam:
/// the payload's memberwise initialiser is private and only an `AnalyticsEvent` can build
/// one, so this type has no way to attach a key docs/17 §3 does not allow. It translates;
/// it does not decide.
///
/// Reaching here already means consent was granted — `AnalyticsRecorder` re-reads the flag
/// before every send — which is also why starting Firebase here is not a leak: a consenting
/// user's first event is exactly when the SDK should come up.
struct FirebaseAnalyticsSink: AnalyticsSending {
    private let bootstrap: FirebaseBootstrap

    init(bootstrap: FirebaseBootstrap = .shared) {
        self.bootstrap = bootstrap
    }

    func send(_ payload: AnalyticsPayload) {
        guard bootstrap.start() else { return }
        Analytics.logEvent(payload.name, parameters: firebaseParameters(payload))
    }
}

/// The SDK's own collection switch.
///
/// Separate from the sink because it governs what Firebase reports *on its own initiative* —
/// `session_start`, `first_open`, `app_update` — none of which passes through
/// `AnalyticsRecorder`. Gating only our own events would leave the SDK talking behind the
/// gate's back.
protocol AnalyticsCollectionControlling: Sendable {
    func setEnabled(_ granted: Bool)
}

/// Drives `Analytics.setAnalyticsCollectionEnabled`.
struct FirebaseAnalyticsCollectionControl: AnalyticsCollectionControlling {
    private let bootstrap: FirebaseBootstrap

    init(bootstrap: FirebaseBootstrap = .shared) {
        self.bootstrap = bootstrap
    }

    func setEnabled(_ granted: Bool) {
        guard granted else {
            // **Switching off must never be the thing that switches Firebase on.** If
            // nothing has started the SDK there is nothing collecting, and starting it
            // here — to tell it not to collect — would be the leak this class prevents.
            if bootstrap.isStarted {
                Analytics.setAnalyticsCollectionEnabled(false)
            }
            return
        }
        guard bootstrap.start() else { return }
        // Overrides the Info.plist default for this install; the SDK persists it, which is
        // why `AnalyticsComposition.applyStoredConsent()` re-asserts the stored flag on
        // every launch rather than trusting what the last session left behind.
        Analytics.setAnalyticsCollectionEnabled(true)
    }
}

/// The control for a build with no Firebase project.
///
/// Doing nothing is correct rather than merely convenient: with no `GoogleService-Info.plist`
/// the SDK has no project to report to, so there is nothing that could collect.
struct NoAnalyticsCollectionControl: AnalyticsCollectionControlling {
    func setEnabled(_: Bool) {}
}

/// The consent store the app actually uses: the persisted flag, and the SDK switch that has
/// to move with it.
///
/// A decorator rather than a call added to `SettingsModel`, so the screen keeps knowing
/// nothing about Firebase and **no path can persist a consent change without the SDK
/// hearing about it** — docs/07's 끄면 즉시 중단한다 holds for whoever writes the flag, not
/// only for the one caller someone remembered to wire.
struct FirebaseGatedAnalyticsConsentStore: AnalyticsConsentStoring {
    private let base: any AnalyticsConsentStoring
    private let collection: any AnalyticsCollectionControlling

    init(base: any AnalyticsConsentStoring, collection: any AnalyticsCollectionControlling) {
        self.base = base
        self.collection = collection
    }

    var isGranted: Bool {
        base.isGranted
    }

    func setGranted(_ granted: Bool) {
        // Persisted first: the stored flag is the truth, and `applyStoredConsent()` re-reads
        // it on the next launch. If the SDK call below failed the user's choice would still
        // have been kept.
        base.setGranted(granted)
        collection.setEnabled(granted)
    }
}
