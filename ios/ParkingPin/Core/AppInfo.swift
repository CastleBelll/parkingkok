import Foundation

/// Read-only identity of the running bundle, resolved from Info.plist.
///
/// Values are injected per configuration by `Config/{Dev,Staging,Prod}.xcconfig`,
/// so this type is how the app learns which environment it was built for.
struct AppInfo: Sendable, Equatable {
    /// Substituted when a key is missing, so no code path needs a force unwrap.
    static let unknownValue = "unknown"

    let displayName: String
    let version: String
    let buildNumber: String
    let environment: String

    init(infoDictionary: [String: Any]?) {
        displayName = Self.string(infoDictionary, InfoKey.displayName)
        version = Self.string(infoDictionary, InfoKey.version)
        buildNumber = Self.string(infoDictionary, InfoKey.buildNumber)
        environment = Self.string(infoDictionary, InfoKey.environment)
    }

    /// e.g. `0.1.0 (1) · dev`
    var versionSummary: String {
        "\(version) (\(buildNumber)) · \(environment)"
    }

    private enum InfoKey {
        static let displayName = "CFBundleDisplayName"
        static let version = "CFBundleShortVersionString"
        static let buildNumber = "CFBundleVersion"
        static let environment = "PKAppEnvironment"
    }

    private static func string(_ dictionary: [String: Any]?, _ key: String) -> String {
        guard let value = dictionary?[key] as? String, !value.isEmpty else {
            return unknownValue
        }
        return value
    }
}

extension AppInfo {
    static var current: AppInfo {
        AppInfo(infoDictionary: Bundle.main.infoDictionary)
    }
}
