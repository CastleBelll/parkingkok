import Foundation

/// What the app believes about this user's subscription (docs/08 §3).
///
/// The six states are the contract's, not a convenience subset: `grace` and `billingIssue`
/// are the two that decide whether a lapsing subscriber keeps their features while Apple or
/// Google retries a payment, and a build that collapsed them into `free` would take Plus
/// away from someone who is still paying for it.
///
/// Nothing produces anything but `.free` and `.plusActive` yet — see [PlusEntitlementSource].
enum PlusEntitlement: String, Sendable, Equatable, CaseIterable {
    /// Before the first store query answers. Fails closed, like every other unknown here.
    case unknown
    case free
    case plusActive
    /// Payment failed, the store is retrying, and access continues meanwhile.
    case grace
    /// The store is asking the user to fix a payment method. Access continues.
    case billingIssue
    case expired

    /// The one question a feature asks. `unknown` is `false`: a wrong `false` costs a
    /// disabled control for a second, a wrong `true` gives a paid feature away for free.
    var allowsPlusFeatures: Bool {
        switch self {
        case .plusActive, .grace, .billingIssue: true
        case .unknown, .free, .expired: false
        }
    }
}

/// **The single place Plus is decided.** docs/06 §7a for the widget, generalised because
/// every other Plus feature in docs/08 §1 — auto departure, unlimited history, export,
/// advanced detector controls — needs the same answer and must not each invent one.
///
/// ### Why it is hardcoded
/// The app ships free first: no StoreKit, no Play Billing, no paywall (docs/08 §1a). So
/// this returns `.plusActive` on DEV and STAGING, where the Plus paths have to be built and
/// tested, and `.free` on PROD, where they must be invisible. M6 replaces the body of
/// `current(environment:)` with a verified store transaction and changes nothing else.
///
/// The environment string is a parameter rather than read inside, so both branches are
/// reachable from a test — a function whose PROD branch could only run on a release build
/// is a function nobody checks.
enum PlusEntitlementSource {
    static func current(environment: String) -> PlusEntitlement {
        switch environment {
        case Environment.dev, Environment.staging: .plusActive
        // PROD, and anything unrecognised: a mis-set `PK_APP_ENV` or a target built without
        // the xcconfig must not hand out a paid feature.
        default: .free
        }
    }

    /// The running bundle's answer. In the widget extension this reads the extension's own
    /// Info.plist, which carries the same `PKAppEnvironment` as the app's.
    static var current: PlusEntitlement {
        current(environment: AppInfo.current.environment)
    }

    private enum Environment {
        static let dev = "dev"
        static let staging = "staging"
    }
}
