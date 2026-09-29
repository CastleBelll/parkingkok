import Foundation
import Testing
@testable import ParkingPin

/// docs/18 §13: DEV and STAGING builds report into `parkingpin-dev`, never production.
///
/// The options file is chosen per configuration by `EXCLUDED_SOURCE_FILE_NAMES`
/// (ios/Config/*.xcconfig). A checkout without the files — CI, forks — ships none, which is
/// allowed; what must never happen is a non-production build carrying the production
/// project, where test accounts and development analytics would mix with real users.
@Suite("Firebase environment")
struct FirebaseEnvironmentTests {
    @Test("A DEV build carries the dev project's options, or none at all")
    func devBuildNeverCarriesProduction() throws {
        // Arrange — the host app of this test bundle is built with the DEV configuration.
        guard let path = Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") else {
            return
        }

        // Act
        let options = try #require(NSDictionary(contentsOfFile: path))

        // Assert
        #expect(options["PROJECT_ID"] as? String == "parkingpin-dev")
    }
}
