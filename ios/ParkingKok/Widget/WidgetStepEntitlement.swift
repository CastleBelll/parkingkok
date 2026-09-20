import Foundation

/// Whether the widget's `−`/`+` keys do anything (docs/06 §7a "Entitlement").
///
/// Interactive stepping is a Plus feature (docs/02 §14, docs/08 §1), so this names the
/// feature and [PlusEntitlementSource] answers whether Plus. It used to carry its own copy
/// of the DEV/STAGING rule; that copy moved down so the next Plus feature does not make a
/// third one.
///
/// **Nothing anywhere else may decide this.** §7a: "entitlement must not be read anywhere
/// but that one function." A second check somewhere in the view layer is how a feature ends
/// up half-gated.
enum WidgetStepEntitlement {
    static func isEnabled(environment: String) -> Bool {
        PlusEntitlementSource.current(environment: environment).allowsPlusFeatures
    }

    /// The running bundle's answer.
    static var isEnabled: Bool {
        PlusEntitlementSource.current.allowsPlusFeatures
    }
}
