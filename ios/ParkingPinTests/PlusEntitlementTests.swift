import Testing
@testable import ParkingPin

/// docs/08 §3's entitlement model, and the free-launch stub standing in for M6.
struct PlusEntitlementTests {
    @Test(
        "A paying subscriber keeps their features while the store retries",
        arguments: [PlusEntitlement.grace, .billingIssue]
    )
    func lapsingSubscriberKeepsAccess(_ state: PlusEntitlement) {
        // §3: access continues, or the app takes Plus away from someone whose card simply
        // expired while Apple was still retrying it.
        #expect(state.allowsPlusFeatures)
    }

    @Test(
        "An unresolved entitlement fails closed",
        arguments: [PlusEntitlement.unknown, .free, .expired]
    )
    func failsClosed(_ state: PlusEntitlement) {
        // A wrong `false` costs a disabled control; a wrong `true` gives a paid feature away.
        #expect(!state.allowsPlusFeatures)
    }

    @Test("A shipped build is free until M6 replaces the source")
    func productionIsFree() {
        // The whole point of shipping free first (docs/08 §1a).
        #expect(PlusEntitlementSource.current(environment: "prod") == .free)
    }

    @Test(
        "DEV and STAGING carry Plus so the paid paths stay testable",
        arguments: ["dev", "staging"]
    )
    func nonProductionCarriesPlus(_ environment: String) {
        #expect(PlusEntitlementSource.current(environment: environment) == .plusActive)
    }

    @Test(
        "An environment the source does not recognise is free",
        arguments: ["", "PROD", "production", "qa", AppInfo.unknownValue]
    )
    func unrecognisedEnvironmentIsFree(_ environment: String) {
        // `PROD` is in the list on purpose: the comparison is case-sensitive, and the safe
        // side of that is off.
        #expect(PlusEntitlementSource.current(environment: environment) == .free)
    }

    @Test("Every state answers the one question")
    func everyStateIsDecided() {
        // A state added later would not compile without a branch; this catches one added
        // with the wrong branch by putting the whole table in one place.
        #expect(
            PlusEntitlement.allCases.map(\.allowsPlusFeatures)
                == [false, false, true, true, true, false]
        )
    }
}
