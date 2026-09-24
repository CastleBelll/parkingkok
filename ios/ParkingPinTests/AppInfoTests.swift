import Testing
@testable import ParkingPin

struct AppInfoTests {
    @Test("Reads every field from a fully populated Info.plist dictionary")
    func readsPopulatedDictionary() {
        // Arrange
        let dictionary: [String: Any] = [
            "CFBundleDisplayName": "ParkingPin DEV",
            "CFBundleShortVersionString": "0.1.0",
            "CFBundleVersion": "1",
            "PKAppEnvironment": "dev"
        ]

        // Act
        let info = AppInfo(infoDictionary: dictionary)

        // Assert
        #expect(info.displayName == "ParkingPin DEV")
        #expect(info.version == "0.1.0")
        #expect(info.buildNumber == "1")
        #expect(info.environment == "dev")
        #expect(info.versionSummary == "0.1.0 (1) · dev")
    }

    @Test("Falls back instead of trapping when the dictionary is nil")
    func fallsBackOnNilDictionary() {
        // Arrange / Act
        let info = AppInfo(infoDictionary: nil)

        // Assert
        #expect(info.displayName == AppInfo.unknownValue)
        #expect(info.version == AppInfo.unknownValue)
        #expect(info.buildNumber == AppInfo.unknownValue)
        #expect(info.environment == AppInfo.unknownValue)
    }

    // `arguments:` would require the cases to be Sendable, which `[String: Any]`
    // is not under Swift 6 strict concurrency, so the cases stay local.
    @Test("Falls back on missing, empty, and wrongly typed values")
    func fallsBackOnUnusableValue() {
        // Arrange
        let unusableDictionaries: [[String: Any]] = [
            [:],
            ["CFBundleDisplayName": ""],
            ["CFBundleDisplayName": 42]
        ]

        for dictionary in unusableDictionaries {
            // Act
            let info = AppInfo(infoDictionary: dictionary)

            // Assert
            #expect(info.displayName == AppInfo.unknownValue)
        }
    }

    @Test("The real bundle resolves a concrete environment")
    func realBundleHasEnvironment() {
        // Arrange / Act
        let info = AppInfo.current

        // Assert
        #expect(info.environment != AppInfo.unknownValue)
    }
}
