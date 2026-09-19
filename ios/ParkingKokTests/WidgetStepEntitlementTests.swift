import Testing
@testable import ParkingKok

/// **docs/06 §7a "Entitlement".**
///
/// "Tests must cover both branches; entitlement must not be read anywhere but that one
/// function." Both branches are here, which is why the gate is a function of the
/// environment string rather than a `#if` — a compile-time switch can only ever be tested
/// in whichever direction the test build happens to point.
struct WidgetStepEntitlementTests {
    @Test("Interactive stepping is on for the builds that are not shipped", arguments: ["dev", "staging"])
    func enabledOutsideProduction(_ environment: String) {
        // Arrange / Act / Assert — `PK_APP_ENV` from Config/{Dev,Staging}.xcconfig.
        #expect(WidgetStepEntitlement.isEnabled(environment: environment))
    }

    @Test("A shipped build is read-only until Plus exists in M6")
    func disabledInProduction() {
        // Arrange / Act / Assert — `PK_APP_ENV` from Config/Prod.xcconfig.
        #expect(!WidgetStepEntitlement.isEnabled(environment: "prod"))
    }

    @Test(
        "An environment the gate does not recognise fails closed",
        arguments: ["", "PROD", "production", "qa", AppInfo.unknownValue]
    )
    func failsClosed(_ environment: String) {
        // Arrange — a mis-set `PK_APP_ENV`, or a bundle with no `PKAppEnvironment` key at
        // all, which is what `AppInfo` reports as `unknown`.
        //
        // Act / Assert — a wrong `false` costs a disabled button; a wrong `true` gives away
        // a paid feature (docs/08 §1). Note `PROD` in the list: the comparison is
        // case-sensitive on purpose, and the safe side of that is off.
        #expect(!WidgetStepEntitlement.isEnabled(environment: environment))
    }

    @Test("The running bundle resolves through the same function, not a second rule")
    func bundleValueMatchesTheFunction() {
        // Arrange — the test bundle runs inside the DEV app, whose Info.plist carries
        // `PKAppEnvironment = dev`.
        let environment = AppInfo.current.environment

        // Act / Assert — this is the whole of `isEnabled`'s live path. If someone adds a
        // second condition to the property, this stops holding.
        #expect(WidgetStepEntitlement.isEnabled == WidgetStepEntitlement.isEnabled(environment: environment))
    }
}
