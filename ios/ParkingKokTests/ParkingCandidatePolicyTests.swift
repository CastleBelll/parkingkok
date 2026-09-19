import Foundation
import Testing
@testable import ParkingKok

/// docs/05_CROSS_PLATFORM_DOMAIN_CONTRACT.md §6 and
/// docs/05_PARKING_DETECTION_ENGINE.md §8/§9, stated as assertions.
///
/// These are the numbers Android has to match, so they are asserted on the *outcome*
/// (candidate or not, which bucket, which codes) rather than on the arithmetic: §12 of the
/// architecture doc makes the fixture outcome the parity mechanism, and a raw score is
/// explicitly allowed to differ.
@Suite("Parking candidate policy")
struct ParkingCandidatePolicyTests {
    private let now = TestTime.reference

    /// A drive that confirmed and ended. Every test starts here and adds one thing.
    private func endedDrive(
        walking: Bool = false,
        stationary: Bool = false,
        locationStopped: Bool = false,
        gpsDegraded: Bool = false,
        projection: Bool = false,
        duration: TimeInterval? = 900,
        distance: Double? = 5000
    ) -> ParkingEvidence {
        ParkingEvidence(
            hasMeaningfulVehicleSession: true,
            vehicleEnded: true,
            walkingAfterVehicle: walking,
            stationaryAfterVehicle: stationary,
            locationStopped: locationStopped,
            gpsQualityDegraded: gpsDegraded,
            carProjectionDisconnected: projection,
            reliableLocationCaptured: true,
            driveDuration: duration,
            driveDistanceMeters: distance
        )
    }

    private func evaluate(_ evidence: ParkingEvidence) -> ParkingCandidate? {
        ParkingCandidatePolicy.evaluate(
            evidence,
            id: UUID(),
            detectedAt: now,
            lastReliableLocation: TestCandidate.location,
            accuracyBucket: .good
        )
    }

    // MARK: - §6: when a candidate exists at all

    @Test("A drive that ended with a walk is a candidate")
    func walkAfterDriveIsCandidate() {
        // Arrange
        let evidence = endedDrive(walking: true)

        // Act
        let candidate = evaluate(evidence)

        // Assert
        #expect(candidate != nil)
    }

    /// §6's three clauses, each removed in turn. Nothing else in the suite proves the
    /// rule is an `AND`.
    @Test(
        "Every clause of the §6 rule is required",
        arguments: [
            ParkingEvidence(hasMeaningfulVehicleSession: false, vehicleEnded: true, walkingAfterVehicle: true),
            ParkingEvidence(hasMeaningfulVehicleSession: true, vehicleEnded: false, walkingAfterVehicle: true),
            ParkingEvidence(hasMeaningfulVehicleSession: true, vehicleEnded: true)
        ]
    )
    func everyClauseIsRequired(evidence: ParkingEvidence) {
        // Act & Assert
        #expect(evaluate(evidence) == nil)
    }

    /// §6, verbatim: "GPS degradation alone cannot satisfy rule."
    @Test("Degraded GPS alone is not a confirmation signal")
    func gpsDegradationAloneIsNotEnough() {
        // Arrange — a real drive that ended, with nothing but the fix quality collapsing.
        let evidence = endedDrive(gpsDegraded: true)

        // Act & Assert
        #expect(evaluate(evidence) == nil)
    }

    // MARK: - §9: buckets

    /// The case acceptance criterion 1 rests on: a drive confirmed only by the device
    /// going still scores below 60 and must therefore never be posted.
    @Test("Stationary-only confirmation scores low and is not notifiable")
    func stationaryOnlyIsLow() throws {
        // Arrange
        let evidence = endedDrive(stationary: true)

        // Act
        let candidate = try #require(evaluate(evidence))

        // Assert — recorded, and silent.
        #expect(candidate.confidenceBucket == .low)
        #expect(!candidate.isNotifiable)
    }

    @Test("A walk after a long drive is medium and is notifiable")
    func walkingIsMedium() throws {
        // Arrange
        let evidence = endedDrive(walking: true)

        // Act
        let candidate = try #require(evaluate(evidence))

        // Assert — 25 + 15 + 30 + 5.
        #expect(candidate.confidenceBucket == .medium)
        #expect(candidate.isNotifiable)
    }

    @Test("A walk with the movement already stopped reaches high")
    func walkingAndLocationStoppedIsHigh() throws {
        // Arrange
        let evidence = endedDrive(walking: true, locationStopped: true)

        // Act
        let candidate = try #require(evaluate(evidence))

        // Assert — 25 + 15 + 30 + 10 + 5 = 85.
        #expect(candidate.confidenceBucket == .high)
    }

    /// §9's edges are a contract, not an implementation detail: Android reads the same
    /// two numbers.
    @Test(
        "§9 bucket edges",
        arguments: [(0, ConfidenceBucket.low), (59, .low), (60, .medium), (79, .medium), (80, .high), (120, .high)]
    )
    func bucketEdges(score: Int, expected: ConfidenceBucket) {
        #expect(ParkingCandidatePolicy.bucket(for: score) == expected)
    }

    // MARK: - §4 reason codes

    @Test("Reason codes describe the evidence, in the contract's own order")
    func reasonCodesFollowEvidence() throws {
        // Arrange — a long drive that ended with a walk, underground.
        let evidence = endedDrive(walking: true, gpsDegraded: true)

        // Act
        let candidate = try #require(evaluate(evidence))

        // Assert
        #expect(candidate.reasonCodes == [
            .recentVehicleActivity,
            .vehicleDurationMet,
            .vehicleDistanceMet,
            .vehicleExitDetected,
            .walkingAfterVehicle,
            .locationQualityDegraded,
            .reliableLocationCaptured
        ])
    }

    /// A short hop clears §7's minimum by neither clause, so the two "met" codes must be
    /// absent — they are what separates a commute from a trip round the block in the
    /// field data.
    @Test("A drive under the §7 minimums carries neither duration nor distance code")
    func shortDriveOmitsMetCodes() throws {
        // Arrange
        let evidence = endedDrive(walking: true, duration: 60, distance: 300)

        // Act
        let candidate = try #require(evaluate(evidence))

        // Assert
        #expect(!candidate.reasonCodes.contains(.vehicleDurationMet))
        #expect(!candidate.reasonCodes.contains(.vehicleDistanceMet))
    }

    // MARK: - docs/17 §3: what may be reported

    /// The privacy boundary, from the other side: whatever the evidence was, the event
    /// carries only §3's properties, and the reason codes reach it as §3's booleans.
    @Test("Analytics properties carry the bucket and the codes' booleans, and nothing else")
    func analyticsPropertiesStayInsideTheAllowlist() throws {
        // Arrange
        let candidate = try #require(evaluate(endedDrive(walking: true, gpsDegraded: true)))

        // Act
        let payload = AnalyticsPayload(.parkingCandidateCreated(candidate.analyticsProperties), occurredAt: now)

        // Assert
        // 25 + 15 + 30 + 5 (degraded) + 5 (comfortably over) = 80.
        #expect(payload.parameters[AnalyticsPayload.Key.confidenceBucket] == .string("high"))
        #expect(payload.parameters[AnalyticsPayload.Key.walkingEvidence] == .bool(true))
        #expect(payload.parameters[AnalyticsPayload.Key.gpsDegradation] == .bool(true))
        #expect(payload.parameters[AnalyticsPayload.Key.optionalVehicleSignal] == .bool(false))
        #expect(Set(payload.parameters.keys).isSubset(of: AnalyticsPayload.Key.all))
    }

    // MARK: - §10 expiry

    @Test("A candidate lives 45 minutes and not a second longer")
    func expiresAfterFortyFiveMinutes() throws {
        // Arrange
        let candidate = try #require(evaluate(endedDrive(walking: true)))

        // Act & Assert
        #expect(candidate.expiresAt == now.addingTimeInterval(45 * 60))
        #expect(!candidate.isExpired(now: now.addingTimeInterval(45 * 60 - 1)))
        #expect(candidate.isExpired(now: now.addingTimeInterval(45 * 60)))
    }
}

/// docs/02 §5 fixes the words and docs/05 §10a / docs/09 §9 fix what may never appear.
/// Both are cheap to break and expensive to notice, so both are held here.
@Suite("Candidate notification contract")
struct CandidateNotificationTests {
    @Test("The copy is docs/02 §5's, to the character")
    func copyIsVerbatim() {
        #expect(CandidateNotificationCopy.title == "주차한 것 같아요")
        #expect(CandidateNotificationCopy.body == "마지막으로 확인된 위치와 시간을 저장해뒀어요.")
        #expect(CandidateNotificationCopy.notParkingTitle == "주차 아님")
    }

    /// docs/10 §7: never state the detection as settled.
    @MainActor
    @Test("No string on this surface claims the parking is done")
    func copyNeverClaimsCompletion() {
        let strings = [
            CandidateNotificationCopy.title,
            CandidateNotificationCopy.body,
            CandidateNotificationCopy.enterFloorTitle,
            CandidateNotificationCopy.notParkingTitle,
            CandidateConfirmationView.savedText
        ]
        #expect(strings.allSatisfy { !$0.contains("주차 완료") })
    }

    /// The deduplication rule from §10a, which is the only reason a session cannot show
    /// two notifications: the identifier is the candidate, so re-posting replaces.
    @Test("The request identifier is the candidate, so a re-post replaces rather than stacks")
    func requestIdentifierIsTheCandidate() {
        // Arrange
        let id = UUID()
        let other = UUID()

        // Act & Assert
        #expect(
            CandidateNotificationAction.requestIdentifier(for: id)
                == CandidateNotificationAction.requestIdentifier(for: id)
        )
        #expect(
            CandidateNotificationAction.requestIdentifier(for: id)
                != CandidateNotificationAction.requestIdentifier(for: other)
        )
    }

    /// The diagnostics prompt and the product notification must never share a channel —
    /// muting one has to be possible without muting the other.
    @Test("The candidate category is not the diagnostics category")
    func categoriesAreSeparate() {
        #expect(CandidateNotificationAction.categoryIdentifier != TraceLabelPromptAction.categoryIdentifier)
    }
}
