import Foundation

/// Whether the widget's `−`/`+` keys do anything (docs/06 §7a "Entitlement").
///
/// Interactive stepping is a Plus feature (docs/02 §14, docs/08 §1). Plus does not exist
/// until M6, so this is the "single hardcoded source — one function, one file" §7a asks
/// for: the interactive path is fully built and tested now while a shipped build stays
/// read-only. M6 replaces the body of `isEnabled(environment:)` and nothing else.
///
/// **Nothing anywhere else may decide this.** §7a: "entitlement must not be read anywhere
/// but that one function." A second check somewhere in the view layer is how a feature
/// ends up half-gated.
enum WidgetStepEntitlement {
    /// `true` for DEV and STAGING, `false` for PROD.
    ///
    /// Fails closed. An environment string this does not recognise — a mis-set
    /// `PK_APP_ENV`, a missing `PKAppEnvironment` key, a widget bundle built without the
    /// xcconfig — is read-only, because a wrong `false` costs a disabled button and a
    /// wrong `true` gives away a paid feature.
    static func isEnabled(environment: String) -> Bool {
        switch environment {
        case Environment.dev, Environment.staging: true
        default: false
        }
    }

    /// The running bundle's answer. In the widget extension this reads the extension's
    /// own Info.plist, which carries the same `PKAppEnvironment` as the app's.
    static var isEnabled: Bool {
        isEnabled(environment: AppInfo.current.environment)
    }

    /// The values `Config/{Dev,Staging,Prod}.xcconfig` put in `PK_APP_ENV`. `prod` is
    /// absent on purpose: it is the default branch, along with everything unrecognised.
    private enum Environment {
        static let dev = "dev"
        static let staging = "staging"
    }
}
