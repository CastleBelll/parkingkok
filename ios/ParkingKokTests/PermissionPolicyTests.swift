import CoreLocation
import CoreMotion
import Testing
@testable import ParkingKok

@Suite("Permission mapping and transitions")
struct PermissionPolicyTests {
    @Test("Core Location statuses map onto the domain enum")
    func mapsLocationStatuses() {
        // Arrange / Act / Assert
        #expect(LocationAuthorization(CLAuthorizationStatus.notDetermined) == .notDetermined)
        #expect(LocationAuthorization(CLAuthorizationStatus.restricted) == .restricted)
        #expect(LocationAuthorization(CLAuthorizationStatus.denied) == .denied)
        #expect(LocationAuthorization(CLAuthorizationStatus.authorizedWhenInUse) == .whenInUse)
        #expect(LocationAuthorization(CLAuthorizationStatus.authorizedAlways) == .always)
    }

    @Test("Core Motion statuses map onto the domain enum")
    func mapsMotionStatuses() {
        // Arrange / Act / Assert
        #expect(MotionAuthorization(CMAuthorizationStatus.notDetermined) == .notDetermined)
        #expect(MotionAuthorization(CMAuthorizationStatus.restricted) == .restricted)
        #expect(MotionAuthorization(CMAuthorizationStatus.denied) == .denied)
        #expect(MotionAuthorization(CMAuthorizationStatus.authorized) == .authorized)
        #expect(MotionAuthorization.authorized.allowsHistoryQuery)
        #expect(!MotionAuthorization.denied.allowsHistoryQuery)
    }

    @Test("First launch asks for When-In-Use, never Always")
    func firstLaunchNeverAsksForAlways() {
        // Arrange — Smart Detection is off by default on a fresh install.
        let request = PermissionRequestPolicy.nextRequest(location: .notDetermined, smartDetectionEnabled: false)

        // Act / Assert — docs/04 §4.
        #expect(request == .whenInUse)
    }

    @Test("Always is withheld until the user opts into Smart Detection")
    func alwaysWaitsForOptIn() {
        // Arrange / Act
        let beforeOptIn = PermissionRequestPolicy.nextRequest(location: .whenInUse, smartDetectionEnabled: false)
        let afterOptIn = PermissionRequestPolicy.nextRequest(location: .whenInUse, smartDetectionEnabled: true)

        // Assert
        #expect(beforeOptIn == nil)
        #expect(afterOptIn == .always)
    }

    @Test("The full ladder: notDetermined → whenInUse → always → nothing left to ask")
    func walksTheLadder() {
        // Arrange
        var status = LocationAuthorization.notDetermined
        var asked: [LocationPermissionRequest] = []

        // Act — the user opts in immediately, then grants each prompt.
        while let request = PermissionRequestPolicy.nextRequest(location: status, smartDetectionEnabled: true) {
            asked.append(request)
            status = request == .whenInUse ? .whenInUse : .always
        }

        // Assert
        #expect(asked == [.whenInUse, .always])
        #expect(status == .always)
    }

    @Test("A denial ends the ladder without re-prompting and is not an app failure")
    func deniedStopsAsking() {
        // Arrange / Act / Assert
        for status in [LocationAuthorization.denied, .restricted] {
            #expect(PermissionRequestPolicy.nextRequest(location: status, smartDetectionEnabled: true) == nil)
            #expect(status.requiresSettingsChange)
            #expect(!PermissionRequestPolicy.shouldMonitorSignificantChanges(
                location: status,
                smartDetectionEnabled: true
            ))
        }
    }

    @Test("Significant-change monitoring needs Always plus the opt-in, and nothing less")
    func monitoringRequiresAlwaysAndOptIn() {
        // Arrange / Act / Assert
        #expect(PermissionRequestPolicy.shouldMonitorSignificantChanges(
            location: .always,
            smartDetectionEnabled: true
        ))
        // When-In-Use cannot relaunch a terminated app, so it must not start the trigger.
        #expect(!PermissionRequestPolicy.shouldMonitorSignificantChanges(
            location: .whenInUse,
            smartDetectionEnabled: true
        ))
        #expect(!PermissionRequestPolicy.shouldMonitorSignificantChanges(
            location: .always,
            smartDetectionEnabled: false
        ))
        #expect(!LocationAuthorization.whenInUse.allowsSignificantLocationMonitoring)
        #expect(LocationAuthorization.always.allowsSignificantLocationMonitoring)
    }

    @Test("Losing Always mid-trip stops monitoring instead of crashing the feature")
    func downgradeStopsMonitoring() {
        // Arrange — the user revokes Always in Settings while Smart Detection stays on.
        let before = PermissionRequestPolicy.shouldMonitorSignificantChanges(
            location: .always,
            smartDetectionEnabled: true
        )

        // Act
        let after = PermissionRequestPolicy.shouldMonitorSignificantChanges(
            location: .whenInUse,
            smartDetectionEnabled: true
        )

        // Assert
        #expect(before)
        #expect(!after)
        // And the app can ask again, because When-In-Use is a re-promptable rung.
        #expect(PermissionRequestPolicy.nextRequest(location: .whenInUse, smartDetectionEnabled: true) == .always)
    }

    /// Regression: the first query is the only way the motion prompt ever appears, so
    /// gating it on `.authorized` deadlocked — no query, no prompt, permanently
    /// `notDetermined`. Observed on a real iPhone as a button that did nothing.
    @Test("notDetermined still permits a history query, or the prompt never appears")
    func notDeterminedPermitsQuery() {
        // Arrange / Act / Assert
        #expect(MotionAuthorization.notDetermined.allowsHistoryQuery)
        #expect(MotionAuthorization.authorized.allowsHistoryQuery)
        #expect(!MotionAuthorization.denied.allowsHistoryQuery)
        #expect(!MotionAuthorization.restricted.allowsHistoryQuery)
    }

    @Test("Only a refusal the user must undo in Settings short-circuits the query")
    func onlySettingsRefusalsShortCircuit() {
        // Arrange / Act / Assert
        for status in MotionAuthorization.allCases {
            #expect(status.allowsHistoryQuery == !status.requiresSettingsChange)
        }
        #expect(MotionAuthorization.denied.requiresSettingsChange)
        #expect(MotionAuthorization.restricted.requiresSettingsChange)
        #expect(!MotionAuthorization.notDetermined.requiresSettingsChange)
    }
}
