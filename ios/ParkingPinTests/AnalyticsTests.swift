import Foundation
import Testing
@testable import ParkingPin

/// Records what a transport would have received. The only way to observe the gate.
final class RecordingAnalyticsSink: AnalyticsSending, @unchecked Sendable {
    private let lock = NSLock()
    private var received: [AnalyticsPayload] = []

    var payloads: [AnalyticsPayload] {
        lock.withLock { received }
    }

    func send(_ payload: AnalyticsPayload) {
        lock.withLock { received.append(payload) }
    }
}

/// In-memory consent, so a test never touches the real `UserDefaults` suite.
final class MutableAnalyticsConsentStore: AnalyticsConsentStoring, @unchecked Sendable {
    private let lock = NSLock()
    private var granted: Bool

    init(granted: Bool = false) {
        self.granted = granted
    }

    var isGranted: Bool {
        lock.withLock { granted }
    }

    func setGranted(_ granted: Bool) {
        lock.withLock { self.granted = granted }
    }
}

/// One sample of every case, so a contract test can walk the whole enum. Sample values are
/// deliberately all-different, which is what lets the "no forbidden key" assertions below
/// see every branch of the payload mapper.
/// Shared with `FirebaseWiringTests`, which holds the Firebase-shaped copy of every payload
/// against the same contract.
enum EventSamples {
    static let detection = DetectionProperties(
        confidenceBucket: .medium,
        driveDurationBucket: .min5To15,
        distanceBucket: .km1To5,
        accuracyBucket: .fair,
        walkingEvidence: true,
        gpsDegradation: false,
        optionalVehicleSignal: true
    )

    static let all: [AnalyticsEvent] = [
        .onboardingCompleted,
        .permissionMotionResult(.granted),
        .permissionLocationLevel(.whenInUse),
        .smartDetectionEnabled(true),
        .parkingCandidateCreated(detection),
        .parkingCandidateConfirmed(detection),
        .parkingCandidateRejected(detection),
        .parkingManualSaved,
        .parkingAutoEnd,
        .widgetFloorChanged(.down),
        .paywallViewed,
        .purchaseCompleted(.appStore),
        .referralShared,
        .referralRedeemed
    ]
}

// MARK: - Consent gate

/// **docs/07 "동의".** Default off, nothing sent and nothing buffered before the opt-in, and
/// a revocation stops transmission immediately.
struct AnalyticsConsentGateTests {
    private static let now = Date(timeIntervalSince1970: 1_780_000_000)

    private func makeRecorder(
        consent: MutableAnalyticsConsentStore,
        sink: RecordingAnalyticsSink
    ) -> AnalyticsRecorder {
        AnalyticsRecorder(consent: consent, sink: sink, clock: FixedDateProvider(Self.now))
    }

    @Test("Consent defaults to off, so a fresh install transmits nothing")
    func defaultsToDenied() {
        // Arrange
        let consent = MutableAnalyticsConsentStore()
        let sink = RecordingAnalyticsSink()
        let recorder = makeRecorder(consent: consent, sink: sink)

        // Act — everything the app can report, before anyone was asked.
        for event in EventSamples.all {
            recorder.record(event)
        }

        // Assert
        #expect(consent.isGranted == false)
        #expect(sink.payloads.isEmpty)
    }

    @Test("Nothing recorded before consent is flushed after it — there is no buffer")
    func doesNotBufferBeforeConsent() {
        // Arrange — three events while opted out.
        let consent = MutableAnalyticsConsentStore()
        let sink = RecordingAnalyticsSink()
        let recorder = makeRecorder(consent: consent, sink: sink)
        recorder.record(.parkingManualSaved)
        recorder.record(.onboardingCompleted)
        recorder.record(.paywallViewed)
        #expect(sink.payloads.isEmpty)

        // Act — the user opts in afterwards.
        consent.setGranted(true)

        // Assert — the backlog does not exist. docs/07: consenting to future reporting is
        // not consenting to the past.
        #expect(sink.payloads.isEmpty)

        // …and the next event does go.
        recorder.record(.referralShared)
        #expect(sink.payloads.map(\.name) == ["referral_shared"])
    }

    @Test("After consent, every event reaches the transport")
    func sendsAfterConsent() {
        // Arrange
        let consent = MutableAnalyticsConsentStore(granted: true)
        let sink = RecordingAnalyticsSink()
        let recorder = makeRecorder(consent: consent, sink: sink)

        // Act
        for event in EventSamples.all {
            recorder.record(event)
        }

        // Assert
        #expect(sink.payloads.count == EventSamples.all.count)
        #expect(Set(sink.payloads.map(\.name)) == AnalyticsEvent.allNames)
    }

    @Test("Switching consent off stops the very next event")
    func stopsImmediatelyOnRevocation() {
        // Arrange — opted in, one event through.
        let consent = MutableAnalyticsConsentStore(granted: true)
        let sink = RecordingAnalyticsSink()
        let recorder = makeRecorder(consent: consent, sink: sink)
        recorder.record(.parkingManualSaved)
        #expect(sink.payloads.count == 1)

        // Act
        consent.setGranted(false)
        recorder.record(.parkingManualSaved)
        recorder.record(.paywallViewed)

        // Assert — nothing after the revocation, with no restart or invalidation step.
        #expect(sink.payloads.count == 1)
    }

    @Test("The consent change itself is never an event")
    func consentChangeIsNotReported() {
        // Arrange
        let consent = MutableAnalyticsConsentStore()
        let sink = RecordingAnalyticsSink()
        _ = makeRecorder(consent: consent, sink: sink)

        // Act — grant, then revoke. Both go through the store, which has no recorder.
        consent.setGranted(true)
        consent.setGranted(false)

        // Assert — docs/07: an event on the grant would be a transmission decided by the
        // state before consent existed. There is no such event in `AnalyticsEvent` to send.
        #expect(sink.payloads.isEmpty)
        #expect(AnalyticsEvent.allNames.contains { $0.contains("consent") } == false)
    }

    @Test("The payload is stamped from the injected clock, never from Date()")
    func stampsFromInjectedClock() {
        // Arrange
        let consent = MutableAnalyticsConsentStore(granted: true)
        let sink = RecordingAnalyticsSink()
        let recorder = makeRecorder(consent: consent, sink: sink)

        // Act
        recorder.record(.parkingManualSaved)

        // Assert
        #expect(sink.payloads.first?.occurredAt == Self.now)
    }
}

// MARK: - Event contract

/// **docs/17 §2 and §3.** The fourteen names, the allowed properties, and the forbidden
/// list being unreachable.
struct AnalyticsEventContractTests {
    private static let now = Date(timeIntervalSince1970: 1_780_000_000)

    /// The names in docs/17 §2, transcribed here so a rename in production fails a test
    /// rather than quietly redefining the contract.
    private static let documentedNames: Set<String> = [
        "onboarding_completed",
        "permission_motion_result",
        "permission_location_level",
        "smart_detection_enabled",
        "parking_candidate_created",
        "parking_candidate_confirmed",
        "parking_candidate_rejected",
        "parking_manual_saved",
        "parking_auto_end",
        "widget_floor_changed",
        "paywall_viewed",
        "purchase_completed",
        "referral_shared",
        "referral_redeemed"
    ]

    @Test("The event set is exactly docs/17 §2's fourteen")
    func namesMatchTheDocument() {
        #expect(AnalyticsEvent.allNames == Self.documentedNames)
        #expect(AnalyticsEvent.allNames.count == 14)
        #expect(Set(EventSamples.all.map(\.name)) == Self.documentedNames)
    }

    @Test("Every payload key is on docs/17 §3's allowlist")
    func payloadKeysAreAllowed() {
        for event in EventSamples.all {
            let payload = AnalyticsPayload(event, occurredAt: Self.now)
            let unexpected = Set(payload.parameters.keys).subtracting(AnalyticsPayload.Key.all)
            #expect(unexpected.isEmpty, "\(payload.name) carried \(unexpected)")
        }
    }

    /// docs/17 §3's forbidden list, plus the neighbouring fields docs/09 §11 names.
    ///
    /// The type system already makes these unreachable — `DetectionProperties` has no such
    /// member and `AnalyticsPayload`'s memberwise init is private, so
    /// `AnalyticsPayload(name: "x", parameters: ["latitude": …], occurredAt: …)` **does not
    /// compile**. This asserts the mapper did not invent one anyway.
    @Test("No payload key names a forbidden field")
    func forbiddenKeysAreAbsent() {
        // Matched per `_`-separated word rather than by substring: `platform` contains
        // "lat", and a test that failed on that would have to be weakened to pass, which
        // is how a guard stops guarding.
        let forbidden: Set = [
            "lat", "latitude", "lon", "lng", "longitude", "coordinate", "coordinates",
            "route", "path", "trace",
            "address", "place", "business",
            "floor", "spot", "zone", "memo", "photo"
        ]
        for event in EventSamples.all {
            let payload = AnalyticsPayload(event, occurredAt: Self.now)
            for key in payload.parameters.keys {
                let words = Set(key.split(separator: "_").map(String.init))
                let offending = words.intersection(forbidden)
                #expect(offending.isEmpty, "\(payload.name) key \(key) names \(offending)")
                #expect(forbidden.contains(key) == false, "\(payload.name) key \(key) is forbidden")
            }
        }
    }

    /// A latitude is a `Double`, and `AnalyticsValue` has no `Double` case — so this is a
    /// statement about a type, verified on every value the contract can produce.
    @Test("Every parameter value is a bucket, a flag or a count — never a measurement")
    func valuesAreDiscrete() {
        for event in EventSamples.all {
            let payload = AnalyticsPayload(event, occurredAt: Self.now)
            for value in payload.parameters.values {
                switch value {
                case let .string(text):
                    // A bucket name or a contract enum, never a formatted number.
                    #expect(Double(text) == nil, "\(payload.name) carried numeric text \(text)")
                case .int, .bool:
                    break
                }
            }
        }
    }

    @Test("Every event carries platform")
    func everyEventCarriesPlatform() {
        for event in EventSamples.all {
            let payload = AnalyticsPayload(event, occurredAt: Self.now)
            #expect(payload.parameters[AnalyticsPayload.Key.platform] == .string("ios"))
        }
    }

    @Test("docs/17 §4: the detection family carries detectorVersion")
    func detectionFamilyCarriesDetectorVersion() {
        let detectionEvents: [AnalyticsEvent] = [
            .parkingCandidateCreated(EventSamples.detection),
            .parkingCandidateConfirmed(EventSamples.detection),
            .parkingCandidateRejected(EventSamples.detection),
            .parkingAutoEnd
        ]
        for event in detectionEvents {
            let payload = AnalyticsPayload(event, occurredAt: Self.now)
            #expect(
                payload.parameters[AnalyticsPayload.Key.detectorVersion] == .int(DetectorVersion.current),
                "\(payload.name) lost detectorVersion"
            )
        }
    }

    @Test("A candidate event carries exactly docs/17 §3's detection properties")
    func candidatePayloadIsTheAllowedSet() {
        // Act
        let payload = AnalyticsPayload(.parkingCandidateCreated(EventSamples.detection), occurredAt: Self.now)

        // Assert
        #expect(payload.parameters == [
            "platform": .string("ios"),
            "confidence_bucket": .string("medium"),
            "drive_duration_bucket": .string("min_5_15"),
            "distance_bucket": .string("km_1_5"),
            "accuracy_bucket": .string("fair"),
            "walking_evidence": .bool(true),
            "gps_degradation": .bool(false),
            "optional_vehicle_signal": .bool(true),
            "detector_version": .int(1)
        ])
    }

    @Test("An unknown bucket is omitted rather than defaulted")
    func absentBucketsAreOmitted() {
        // Arrange — a candidate whose trip length and accuracy are not known.
        let sparse = DetectionProperties(
            confidenceBucket: .low,
            driveDurationBucket: nil,
            distanceBucket: nil,
            accuracyBucket: nil,
            walkingEvidence: false,
            gpsDegradation: true,
            optionalVehicleSignal: false
        )

        // Act
        let payload = AnalyticsPayload(.parkingCandidateRejected(sparse), occurredAt: Self.now)

        // Assert — an absent property is honest; a defaulted bucket would invent a trip.
        #expect(payload.parameters[AnalyticsPayload.Key.driveDurationBucket] == nil)
        #expect(payload.parameters[AnalyticsPayload.Key.distanceBucket] == nil)
        #expect(payload.parameters[AnalyticsPayload.Key.accuracyBucket] == nil)
        #expect(payload.parameters[AnalyticsPayload.Key.confidenceBucket] == .string("low"))
    }

    @Test("smart_detection_enabled reports the direction of the change")
    func smartDetectionCarriesTheFlag() {
        #expect(
            AnalyticsPayload(.smartDetectionEnabled(true), occurredAt: Self.now)
                .parameters[AnalyticsPayload.Key.enabled] == .bool(true)
        )
        #expect(
            AnalyticsPayload(.smartDetectionEnabled(false), occurredAt: Self.now)
                .parameters[AnalyticsPayload.Key.enabled] == .bool(false)
        )
    }

    @Test("parking_manual_saved says only which platform saved")
    func manualSavedIsBare() {
        let payload = AnalyticsPayload(.parkingManualSaved, occurredAt: Self.now)
        #expect(payload.parameters == ["platform": .string("ios")])
    }
}

// MARK: - Buckets

/// The bands the contract reports instead of measurements.
struct AnalyticsBucketTests {
    @Test(
        "Drive duration bands",
        arguments: [
            (0.0, DriveDurationBucket.under5Min),
            (299.0, .under5Min),
            (300.0, .min5To15),
            (899.0, .min5To15),
            (900.0, .min15To45),
            (2699.0, .min15To45),
            (2700.0, .over45Min),
            (86400.0, .over45Min)
        ]
    )
    func driveDurationBands(seconds: TimeInterval, expected: DriveDurationBucket) {
        #expect(DriveDurationBucket(seconds: seconds) == expected)
    }

    @Test("A negative duration gets no band, so a backwards clock stays visible")
    func negativeDurationHasNoBucket() {
        #expect(DriveDurationBucket(seconds: -1) == nil)
    }

    @Test(
        "Distance bands",
        arguments: [
            (0.0, DistanceBucket.under1Km),
            (999.0, .under1Km),
            (1000.0, .km1To5),
            (4999.0, .km1To5),
            (5000.0, .km5To20),
            (19999.0, .km5To20),
            (20000.0, .over20Km),
        ]
    )
    func distanceBands(meters: Double, expected: DistanceBucket) {
        #expect(DistanceBucket(meters: meters) == expected)
    }

    @Test("A negative distance gets no band")
    func negativeDistanceHasNoBucket() {
        #expect(DistanceBucket(meters: -0.5) == nil)
    }

    /// The two platforms have to agree on these strings or the numbers cannot be added up.
    @Test("Bucket wire values are the cross-platform ones")
    func wireValuesAreStable() {
        #expect(DriveDurationBucket.allCases.map(\.rawValue) == [
            "under_5_min", "min_5_15", "min_15_45", "over_45_min"
        ])
        #expect(DistanceBucket.allCases.map(\.rawValue) == [
            "under_1_km", "km_1_5", "km_5_20", "over_20_km"
        ])
        #expect(ConfidenceBucket.allCases.map(\.rawValue) == ["high", "medium", "low"])
        #expect(LocationAccuracyBucket.allCases.map(\.rawValue) == ["good", "fair", "poor"])
        #expect(MotionPermissionResult.allCases.map(\.rawValue) == [
            "granted", "denied", "restricted", "not_determined"
        ])
        #expect(LocationPermissionLevel.allCases.map(\.rawValue) == [
            "always", "when_in_use", "denied", "restricted", "not_determined"
        ])
    }
}

// MARK: - Call-site wiring

/// The two events this milestone actually wires (the rest of the contract is type layer
/// only, by design — docs/17's remaining events land with the features that emit them).
@MainActor
struct AnalyticsWiringTests {
    private static let now = Date(timeIntervalSince1970: 1_780_000_000)

    @Test("A manual save reports parking_manual_saved, once, and carries no typed field")
    func manualSaveIsReported() async throws {
        // Arrange
        let consent = MutableAnalyticsConsentStore(granted: true)
        let sink = RecordingAnalyticsSink()
        let clock = FixedDateProvider(Self.now)
        let model = try ParkingModel(
            store: SwiftDataParkingStore(
                container: SwiftDataParkingStore.makeInMemoryContainer(),
                clock: clock
            ),
            locationProvider: UnavailableParkingLocationProvider(),
            clock: clock,
            analytics: AnalyticsRecorder(consent: consent, sink: sink, clock: clock)
        )

        // Act
        let saved = await model.saveManualParking(
            ManualParkingDraft(floorText: "B3", zone: "A구역", spot: "142", memo: "기둥 옆")
        )

        // Assert
        #expect(saved)
        #expect(sink.payloads.map(\.name) == ["parking_manual_saved"])
        // The floor the user typed is nowhere in the payload — it has nowhere to be.
        #expect(sink.payloads.first?.parameters == ["platform": .string("ios")])
    }

    @Test("A manual save that could not be written reports nothing")
    func failedSaveIsNotReported() async throws {
        // Arrange — FR-004 allows one active parking, so a second save fails.
        let consent = MutableAnalyticsConsentStore(granted: true)
        let sink = RecordingAnalyticsSink()
        let clock = FixedDateProvider(Self.now)
        let model = try ParkingModel(
            store: SwiftDataParkingStore(
                container: SwiftDataParkingStore.makeInMemoryContainer(),
                clock: clock
            ),
            locationProvider: UnavailableParkingLocationProvider(),
            clock: clock,
            analytics: AnalyticsRecorder(consent: consent, sink: sink, clock: clock)
        )
        await model.saveManualParking(ManualParkingDraft())

        // Act
        let second = await model.saveManualParking(ManualParkingDraft())

        // Assert — one write, one event. An event for a failed save would overstate use.
        #expect(second == false)
        #expect(sink.payloads.count == 1)
    }

    @Test("A manual save while opted out reports nothing at all")
    func manualSaveWithoutConsentIsSilent() async throws {
        // Arrange
        let sink = RecordingAnalyticsSink()
        let clock = FixedDateProvider(Self.now)
        let model = try ParkingModel(
            store: SwiftDataParkingStore(
                container: SwiftDataParkingStore.makeInMemoryContainer(),
                clock: clock
            ),
            locationProvider: UnavailableParkingLocationProvider(),
            clock: clock,
            analytics: AnalyticsRecorder(
                consent: MutableAnalyticsConsentStore(granted: false),
                sink: sink,
                clock: clock
            )
        )

        // Act
        let saved = await model.saveManualParking(ManualParkingDraft())

        // Assert — the save is unaffected by the opt-out; only the report is.
        #expect(saved)
        #expect(sink.payloads.isEmpty)
    }
}

// MARK: - Persisted consent

/// The on-disk half of the gate.
struct AnalyticsConsentStoreTests {
    private func makeDefaults() throws -> UserDefaults {
        let suite = "pk.tests.analytics.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defaults.removePersistentDomain(forName: suite)
        return defaults
    }

    @Test("A store with nothing written reads as denied")
    func absentKeyReadsAsDenied() throws {
        let store = try UserDefaultsAnalyticsConsentStore(defaults: makeDefaults())
        #expect(store.isGranted == false)
    }

    @Test("Consent survives a new store over the same defaults")
    func consentPersists() throws {
        // Arrange
        let defaults = try makeDefaults()
        UserDefaultsAnalyticsConsentStore(defaults: defaults).setGranted(true)

        // Act — a fresh instance, as a relaunch would build.
        let reopened = UserDefaultsAnalyticsConsentStore(defaults: defaults)

        // Assert
        #expect(reopened.isGranted)

        // …and a revocation persists the same way.
        reopened.setGranted(false)
        #expect(UserDefaultsAnalyticsConsentStore(defaults: defaults).isGranted == false)
    }
}
